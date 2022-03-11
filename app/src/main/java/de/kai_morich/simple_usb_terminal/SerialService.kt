package de.kai_morich.simple_usb_terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.preference.PreferenceManager
import android.preference.PreferenceManager.getDefaultSharedPreferencesName
import androidx.core.app.NotificationCompat
import de.kai_morich.simple_usb_terminal.Constants.CURRENT_DATAFILE_TIMESTAMP
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.nio.file.attribute.PosixFilePermission.GROUP_READ
import java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
import java.util.*
import java.util.concurrent.Executors

/**
 * Create notification and queue serial data while activity is not in the foreground
 * use listener chain: SerialSocket -> SerialService -> UI fragment
 */
class SerialService : Service(), SerialListener {
    internal inner class SerialBinder : Binder() {
        val service: SerialService
            get() = this@SerialService
    }

    private enum class QueueType {
        CONNECT, CONNECT_ERROR, READ, IO_ERROR
    }


    private class QueueItem constructor(var type: QueueType, var data: ByteArray?, var e: Exception?)
    private val mainLooper = Handler(Looper.getMainLooper())
    private val binder = SerialBinder()
    private val queue1 = LinkedList<QueueItem>()
    private val queue2 = LinkedList<QueueItem>()
    private var socket: SerialSocket? = null
    private var listener: SerialListener? = null
    private var connected = false
    private lateinit var file: File
    private lateinit var asyncFileChannel: AsynchronousFileChannel
    private object FILE_ATTRIBUTES: FileAttribute<Set<PosixFilePermission>> {
        override fun name(): String {
            return "posix:permissions"
        }

        override fun value(): Set<PosixFilePermission>{
            return setOf(OWNER_READ, OWNER_WRITE, GROUP_READ, GROUP_WRITE)
        }
    }


    override fun onCreate() {
        super.onCreate()
        val filename = "oss_${System.currentTimeMillis()}.csv"
        PreferenceManager.getDefaultSharedPreferences(this).edit().putString(CURRENT_DATAFILE_TIMESTAMP, filename).apply()
        file = File(applicationContext.getExternalFilesDirs(null)[0], filename)
        // Single-threaded async file write
        asyncFileChannel = AsynchronousFileChannel.open(
            Paths.get(file.path), setOf(WRITE, CREATE_NEW),
            Executors.newSingleThreadExecutor(),
            FILE_ATTRIBUTES)

        asyncFileChannel.write(ByteBuffer.wrap("data\n".encodeToByteArray()), asyncFileChannel.size())
    }



    override fun onDestroy() {
        cancelNotification()
        disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    /**
     * Api
     */
    @Throws(IOException::class)
    fun connect(socket: SerialSocket) {
        socket.connect(this)
        this.socket = socket
        connected = true
    }

    fun disconnect() {
        connected = false // ignore data,errors while disconnecting
        cancelNotification()
        if (socket != null) {
            socket?.disconnect()
            socket = null
        }
    }

    @Throws(IOException::class)
    fun write(data: ByteArray?) {
        if (!connected) throw IOException("not connected")
        socket?.write(data)
    }

    fun attach(listener: SerialListener) {
        require(!(Looper.getMainLooper().thread !== Thread.currentThread())) { "not in main thread" }
        cancelNotification()
        // use synchronized() to prevent new items in queue2
        // new items will not be added to queue1 because mainLooper.post and attach() run in main thread
        synchronized(this) { this.listener = listener }
        for (item in queue1) {
            when (item.type) {
                QueueType.CONNECT -> listener.onSerialConnect()
                QueueType.CONNECT_ERROR -> listener.onSerialConnectError(item.e)
                QueueType.READ -> listener.onSerialRead(item.data)
                QueueType.IO_ERROR -> listener.onSerialIoError(item.e)
            }
        }
        for (item in queue2) {
            when (item.type) {
                QueueType.CONNECT -> listener.onSerialConnect()
                QueueType.CONNECT_ERROR -> listener.onSerialConnectError(item.e)
                QueueType.READ -> listener.onSerialRead(item.data)
                QueueType.IO_ERROR -> listener.onSerialIoError(item.e)
            }
        }
        queue1.clear()
        queue2.clear()
    }

    fun detach() {
        if (connected) createNotification()
        // items already in event queue (posted before detach() to mainLooper) will end up in queue1
        // items occurring later, will be moved directly to queue2
        // detach() and mainLooper.post run in the main thread, so all items are caught
        listener = null
    }

    /**
     * SerialListener
     */
    override fun onSerialConnect() {
        if (connected) {
            synchronized(this) {
                if (listener != null) {
                    mainLooper.post {
                        if (listener != null) {
                            listener?.onSerialConnect()
                        } else {
                            queue1.add(QueueItem(QueueType.CONNECT, null, null))
                        }
                    }
                } else {
                    queue2.add(QueueItem(QueueType.CONNECT, null, null))
                }
            }
        }
    }

    override fun onSerialConnectError(e: Exception) {
        if (connected) {
            synchronized(this) {
                if (listener != null) {
                    mainLooper.post {
                        if (listener != null) {
                            listener?.onSerialConnectError(e)
                        } else {
                            queue1.add(QueueItem(QueueType.CONNECT_ERROR, null, e))
                            cancelNotification()
                            disconnect()
                        }
                    }
                } else {
                    queue2.add(QueueItem(QueueType.CONNECT_ERROR, null, e))
                    cancelNotification()
                    disconnect()
                }
            }
        }
    }

    override fun onSerialRead(data: ByteArray) {
        if (connected) {
            synchronized(this) {
                if (listener != null) {
                    mainLooper.post {
                        if (listener != null) {
                            listener?.onSerialRead(data)
                        } else {
                            queue1.add(QueueItem(QueueType.READ, data, null))
                        }
                    }
                } else {
                    queue2.add(QueueItem(QueueType.READ, data, null))
                }

                // Always write to disk:
                //asyncFileChannel.write(ByteBuffer.wrap("${System.currentTimeMillis()},${String(data)}".encodeToByteArray()), asyncFileChannel.size())
                asyncFileChannel.write(ByteBuffer.wrap(String(data).encodeToByteArray()), asyncFileChannel.size())
            }
        }
    }

    override fun onSerialIoError(e: Exception) {
        if (connected) {
            synchronized(this) {
                if (listener != null) {
                    mainLooper.post {
                        if (listener != null) {
                            listener?.onSerialIoError(e)
                        } else {
                            queue1.add(QueueItem(QueueType.IO_ERROR, null, e))
                            cancelNotification()
                            disconnect()
                        }
                    }
                } else {
                    queue2.add(QueueItem(QueueType.IO_ERROR, null, e))
                    cancelNotification()
                    disconnect()
                }
            }
        }
    }


    private fun createNotification() {
        val nc = NotificationChannel(Constants.NOTIFICATION_CHANNEL, "Background service", IMPORTANCE_HIGH)
        nc.setShowBadge(false)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(nc)
        val disconnectIntent = Intent()
            .setAction(Constants.INTENT_ACTION_DISCONNECT)
        val restartIntent = Intent()
            .setClassName(this, Constants.INTENT_CLASS_MAIN_ACTIVITY)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        val disconnectPendingIntent = PendingIntent.getBroadcast(this, 1, disconnectIntent, FLAG_IMMUTABLE)
        val restartPendingIntent = PendingIntent.getActivity(this, 1, restartIntent, FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(resources.getColor(R.color.colorPrimary))
            .setContentTitle(resources.getString(R.string.app_name))
            .setContentText(if (socket != null) "Connected to " + socket?.name else "Background Service")
            .setContentIntent(restartPendingIntent)
            .setOngoing(true)
            .addAction(NotificationCompat.Action(R.drawable.ic_clear_white_24dp, "Disconnect", disconnectPendingIntent))
        // @drawable/ic_notification created with Android Studio -> New -> Image Asset using @color/colorPrimaryDark as background color
        // Android < API 21 does not support vectorDrawables in notifications, so both drawables used here, are created as .png instead of .xml
        val notification = builder.build()
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification)
    }

    private fun cancelNotification() {
        stopForeground(true)
    }
}