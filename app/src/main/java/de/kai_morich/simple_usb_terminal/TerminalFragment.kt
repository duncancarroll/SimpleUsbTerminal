package de.kai_morich.simple_usb_terminal

import com.hoho.android.usbserial.driver.UsbSerialPort
import android.widget.TextView
import de.kai_morich.simple_usb_terminal.TextUtil.HexWatcher
import android.os.Bundle
import android.app.Activity
import android.app.AlertDialog
import android.os.IBinder
import de.kai_morich.simple_usb_terminal.SerialService.SerialBinder
import android.text.method.ScrollingMovementMethod
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialProber
import android.app.PendingIntent
import android.content.*
import android.os.Handler
import android.widget.Toast
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.Spannable
import com.hoho.android.usbserial.driver.SerialTimeoutException
import android.widget.LinearLayout
import com.hoho.android.usbserial.driver.UsbSerialPort.ControlLine
import android.os.Looper
import android.view.*
import android.widget.ToggleButton
import androidx.fragment.app.Fragment
import java.io.IOException
import java.lang.Exception
import java.lang.StringBuilder
import java.util.concurrent.TimeUnit

class TerminalFragment : Fragment(), ServiceConnection, SerialListener {
    private enum class Connected {
        FALSE, PENDING, TRUE
    }

    private val REFRESH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1)
    private val MAX_LINES = 10000
    private var handler: Handler = Handler(Looper.getMainLooper())
    private var service: SerialService? = null
    private val broadcastReceiver: BroadcastReceiver
    private var deviceId = 0
    private var portNum = 0
    private var baudRate = 0
    private var usbSerialPort: UsbSerialPort? = null
    private var chartView: ChartView? = null
    private var receiveText: TextView? = null
    private var sendText: TextView? = null
    private var controlLines: ControlLines? = null
    private var hexWatcher: HexWatcher? = null
    private var connected = Connected.FALSE
    private var initialStart = true
    private var hexEnabled = false
    private var controlLinesEnabled = false
    private var pendingNewline = false
    private var newline = TextUtil.newline_crlf


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        retainInstance = true
        deviceId = arguments?.getInt("device") ?: 0
        portNum = arguments?.getInt("port") ?: 0
        baudRate = arguments?.getInt("baud") ?: 0
    }


    /*
     * UI
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        receiveText = view.findViewById(R.id.receive_text) // TextView performance decreases with number of spans
        receiveText?.setTextColor(resources.getColor(R.color.colorRecieveText)) // set as default color to reduce number of spans
        receiveText?.movementMethod = ScrollingMovementMethod.getInstance()
        chartView = view.findViewById(R.id.chart)
        sendText = view.findViewById(R.id.send_text)
        hexWatcher = HexWatcher(sendText)
        hexWatcher?.enable(hexEnabled)
        sendText?.addTextChangedListener(hexWatcher)
        sendText?.hint = if (hexEnabled) "HEX mode" else ""
        val sendBtn = view.findViewById<View>(R.id.send_btn)
        sendBtn.setOnClickListener { v: View? -> send("${sendText?.text}") }
        controlLines = ControlLines(view)
        return view
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_terminal, menu)
        menu.findItem(R.id.hex).isChecked = hexEnabled
        menu.findItem(R.id.controlLines).isChecked = controlLinesEnabled
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.clear -> {
                receiveText?.text = ""
                true
            }
            R.id.newline -> {
                val newlineNames = resources.getStringArray(R.array.newline_names)
                val newlineValues = resources.getStringArray(R.array.newline_values)
                val pos = listOf(*newlineValues).indexOf(newline)
                val builder = AlertDialog.Builder(activity)
                builder.setTitle("Newline")
                builder.setSingleChoiceItems(newlineNames, pos) { dialog: DialogInterface, item1: Int ->
                    newline = newlineValues[item1]
                    dialog.dismiss()
                }
                builder.create().show()
                true
            }
            R.id.hex -> {
                hexEnabled = !hexEnabled
                sendText?.text = ""
                hexWatcher?.enable(hexEnabled)
                sendText?.hint = if (hexEnabled) "HEX mode" else ""
                item.isChecked = hexEnabled
                true
            }
            R.id.controlLines -> {
                controlLinesEnabled = !controlLinesEnabled
                item.isChecked = controlLinesEnabled
                if (controlLinesEnabled) {
                    controlLines?.start()
                } else {
                    controlLines?.stop()
                }
                true
            }
            R.id.sendBreak -> {
                try {
                    usbSerialPort?.setBreak(true)
                    Thread.sleep(100)
                    status("send BREAK")
                    usbSerialPort?.setBreak(false)
                } catch (e: Exception) {
                    status("send BREAK failed: " + e.message)
                }
                true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }


    override fun onDestroy() {
        if (connected != Connected.FALSE) disconnect()
        activity?.stopService(Intent(activity, SerialService::class.java))
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        // prevents service destroy on unbind from recreated activity caused by orientation change
        if (service != null) service?.attach(this) else activity?.startService(Intent(activity, SerialService::class.java))
    }

    override fun onStop() {
        if (service != null && activity?.isChangingConfigurations == false) service?.detach()
        super.onStop()
        stopRefreshHandler()
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        getActivity()?.bindService(Intent(getActivity(), SerialService::class.java), this, Context.BIND_AUTO_CREATE)
    }

    override fun onDetach() {
        try {
            activity?.unbindService(this)
        } catch (ignored: Exception) {
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        activity?.registerReceiver(broadcastReceiver, IntentFilter(Constants.INTENT_ACTION_GRANT_USB))
        if (initialStart && service != null) {
            initialStart = false
            activity?.runOnUiThread { connect() }
        }
        if (controlLinesEnabled && controlLines != null && connected == Connected.TRUE) controlLines?.start()
    }

    override fun onPause() {
        activity?.unregisterReceiver(broadcastReceiver)
        if (controlLines != null) controlLines?.stop()
        super.onPause()
        stopRefreshHandler()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as SerialBinder).service
        service?.attach(this)
        if (initialStart && isResumed) {
            initialStart = false
            activity?.runOnUiThread { connect() }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    /*
     * Serial + UI
     */
    private fun connect(permissionGranted: Boolean? = null) {
        var device: UsbDevice? = null
        val usbManager = activity?.getSystemService(Context.USB_SERVICE) as UsbManager
        for (v in usbManager.deviceList.values) if (v.deviceId == deviceId) device = v
        if (device == null) {
            status("connection failed: device not found")
            return
        }
        var driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device)
        }
        if (driver == null) {
            status("connection failed: no driver for device")
            return
        }
        if (driver.ports.size < portNum) {
            status("connection failed: not enough ports at device")
            return
        }
        usbSerialPort = driver.ports[portNum]
        val usbConnection = usbManager.openDevice(driver.device)
        if (usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.device)) {
            val usbPermissionIntent = PendingIntent.getBroadcast(activity, 0, Intent(Constants.INTENT_ACTION_GRANT_USB), 0)
            usbManager.requestPermission(driver.device, usbPermissionIntent)
            return
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.device)) status("connection failed: permission denied") else status("connection failed: open failed")
            return
        }
        connected = Connected.PENDING
        try {
            usbSerialPort?.open(usbConnection)
            usbSerialPort?.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val socket = SerialSocket(activity?.applicationContext, usbConnection, usbSerialPort)
            service?.connect(socket)
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect()
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.FALSE
        controlLines?.stop()
        service?.disconnect()
        usbSerialPort = null
        stopRefreshHandler()
    }

    private fun send(str: String) {
        if (connected != Connected.TRUE) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val msg: String
            val data: ByteArray
            if (hexEnabled) {
                val sb = StringBuilder()
                TextUtil.toHexString(sb, TextUtil.fromHexString(str))
                TextUtil.toHexString(sb, newline.toByteArray())
                msg = sb.toString()
                data = TextUtil.fromHexString(msg)
            } else {
                msg = str
                data = (str + newline).toByteArray()
            }
            val spn = SpannableStringBuilder(
                """
    $msg
    
    """.trimIndent()
            )
            spn.setSpan(ForegroundColorSpan(resources.getColor(R.color.colorSendText)), 0, spn.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            receiveText?.append(spn)
            service?.write(data)
        } catch (e: SerialTimeoutException) {
            status("write timeout: " + e.message)
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun receive(data: ByteArray) {
        if (hexEnabled) {
            receiveText?.append(
                """
    ${TextUtil.toHexString(data)}
    
    """.trimIndent()
            )
        } else {
            var msg = String(data)
            if (newline == TextUtil.newline_crlf && msg.length > 0) {
                // don't show CR as ^M if directly before LF
                msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf)
                // special handling if CR and LF come in separate fragments
                if (pendingNewline && msg[0] == '\n') {
                    val edt = receiveText?.editableText
                    if (edt != null && edt.length > 1) edt.replace(edt.length - 2, edt.length, "")
                }
                pendingNewline = msg[msg.length - 1] == '\r'
            }
            receiveText?.append(TextUtil.toCaretString(msg, newline.length != 0))

            // Clear text beyond buffer size
            if (receiveText?.lineCount ?: 0 > MAX_LINES) {
                receiveText?.text = ""
            }
        }
    }

    fun status(str: String) {
        val spn = SpannableStringBuilder(
            """
    $str
    
    """.trimIndent()
        )
        spn.setSpan(ForegroundColorSpan(resources.getColor(R.color.colorStatusText)), 0, spn.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        receiveText?.append(spn)
    }

    /*
     * SerialListener
     */
    override fun onSerialConnect() {
        status("connected")
        connected = Connected.TRUE
        if (controlLinesEnabled) controlLines?.start()
        context?.let {
            startRefreshHandler()
        }
    }

    override fun onSerialConnectError(e: Exception) {
        status("connection failed: " + e.message)
        disconnect()
    }

    override fun onSerialRead(data: ByteArray) {
        receive(data)
    }

    override fun onSerialIoError(e: Exception) {
        status("connection lost: " + e.message)
        disconnect()
    }

    private fun startRefreshHandler() {
        stopRefreshHandler()
        handler.postDelayed({
            context?.let {
                chartView?.reloadData(it)
                startRefreshHandler()
            }
        }, REFRESH_INTERVAL_MS)
    }

    private fun stopRefreshHandler() {
        handler.removeCallbacksAndMessages(null)
    }


    inner class ControlLines(view: View) {

        private val refreshInterval = 200 // msec

        private val mainLooper = Handler(Looper.getMainLooper())
        private val runnable: Runnable
        private val frame: LinearLayout
        private val rtsBtn: ToggleButton
        private val ctsBtn: ToggleButton
        private val dtrBtn: ToggleButton
        private val dsrBtn: ToggleButton
        private val cdBtn: ToggleButton
        private val riBtn: ToggleButton
        private fun toggle(v: View) {
            val btn = v as ToggleButton
            if (connected != Connected.TRUE) {
                btn.isChecked = !btn.isChecked
                Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
                return
            }
            var ctrl = ""
            try {
                if (btn == rtsBtn) {
                    ctrl = "RTS"
                    usbSerialPort?.rts = btn.isChecked
                }
                if (btn == dtrBtn) {
                    ctrl = "DTR"
                    usbSerialPort?.dtr = btn.isChecked
                }
            } catch (e: IOException) {
                status("set" + ctrl + " failed: " + e.message)
            }
        }

        private fun run() {
            if (connected != Connected.TRUE) return
            try {
                val controlLines = usbSerialPort?.controlLines
                rtsBtn.isChecked = controlLines?.contains(ControlLine.RTS) == true
                ctsBtn.isChecked = controlLines?.contains(ControlLine.CTS) == true
                dtrBtn.isChecked = controlLines?.contains(ControlLine.DTR) == true
                dsrBtn.isChecked = controlLines?.contains(ControlLine.DSR) == true
                cdBtn.isChecked = controlLines?.contains(ControlLine.CD) == true
                riBtn.isChecked = controlLines?.contains(ControlLine.RI) == true
                mainLooper.postDelayed(runnable, refreshInterval.toLong())
            } catch (e: IOException) {
                status("getControlLines() failed: " + e.message + " -> stopped control line refresh")
            }
        }

        fun start() {
            frame.visibility = View.VISIBLE
            if (connected != Connected.TRUE) return
            try {
                val controlLines = usbSerialPort?.supportedControlLines
                if (controlLines?.contains(ControlLine.RTS) == false) rtsBtn.visibility = View.INVISIBLE
                if (controlLines?.contains(ControlLine.CTS) == false) ctsBtn.visibility = View.INVISIBLE
                if (controlLines?.contains(ControlLine.DTR) == false) dtrBtn.visibility = View.INVISIBLE
                if (controlLines?.contains(ControlLine.DSR) == false) dsrBtn.visibility = View.INVISIBLE
                if (controlLines?.contains(ControlLine.CD) == false) cdBtn.visibility = View.INVISIBLE
                if (controlLines?.contains(ControlLine.RI) == false) riBtn.visibility = View.INVISIBLE
                run()
            } catch (e: IOException) {
                Toast.makeText(activity, "getSupportedControlLines() failed: " + e.message, Toast.LENGTH_SHORT).show()
            }
        }

        fun stop() {
            frame.visibility = View.GONE
            mainLooper.removeCallbacks(runnable)
            rtsBtn.isChecked = false
            ctsBtn.isChecked = false
            dtrBtn.isChecked = false
            dsrBtn.isChecked = false
            cdBtn.isChecked = false
            riBtn.isChecked = false
        }

        init {
            runnable = Runnable { this.run() } // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks
            frame = view.findViewById(R.id.controlLines)
            rtsBtn = view.findViewById(R.id.controlLineRts)
            ctsBtn = view.findViewById(R.id.controlLineCts)
            dtrBtn = view.findViewById(R.id.controlLineDtr)
            dsrBtn = view.findViewById(R.id.controlLineDsr)
            cdBtn = view.findViewById(R.id.controlLineCd)
            riBtn = view.findViewById(R.id.controlLineRi)
            rtsBtn.setOnClickListener { v: View -> toggle(v) }
            dtrBtn.setOnClickListener { v: View -> toggle(v) }
        }
    }

    init {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (Constants.INTENT_ACTION_GRANT_USB == intent.action) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    connect(granted)
                }
            }
        }
    }
}