package de.kai_morich.simple_usb_terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.preference.PreferenceManager
import android.util.AttributeSet
import android.view.View
import org.apache.commons.io.input.ReversedLinesFileReader
import java.io.File
import java.lang.Exception
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.stream.Collectors

class ChartView : View {
    private var _exampleColor: Int = Color.RED
    private var _exampleDimension: Float = 0f
    private lateinit var paint: Paint
    private var file: File? = null
    private var filename: String? = null
    private var reverseReader: ReversedLinesFileReader? = null

    /**
     * The font color
     */
    var exampleColor: Int
        get() = _exampleColor
        set(value) {
            _exampleColor = value
            invalidateTextPaintAndMeasurements()
        }

    /**
     * In the example view, this dimension is the font size.
     */
    var exampleDimension: Float
        get() = _exampleDimension
        set(value) {
            _exampleDimension = value
            invalidateTextPaintAndMeasurements()
        }

    constructor(context: Context) : super(context) {
        init(context, null, 0)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(context, attrs, 0)
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) {
        init(context, attrs, defStyle)
    }

    private fun init(context: Context, attrs: AttributeSet?, defStyle: Int) {
        // Load attributes
        val a = context.obtainStyledAttributes(attrs, R.styleable.ChartView, defStyle, 0)

        _exampleColor = a.getColor(R.styleable.ChartView_exampleColor, exampleColor)
        // Use getDimensionPixelSize or getDimensionPixelOffset when dealing with
        // values that should fall on pixel boundaries.
        _exampleDimension = a.getDimension(R.styleable.ChartView_exampleDimension, exampleDimension)

        a.recycle()

        // Update TextPaint and text measurements from attributes
        invalidateTextPaintAndMeasurements()
        reloadData(context)
    }

    fun reloadData(context: Context) {
        // Read current file from storage
        filename = PreferenceManager.getDefaultSharedPreferences(context).getString(Constants.CURRENT_DATAFILE_TIMESTAMP, null)
        filename?.let {
            file = File(context.getExternalFilesDirs(null)[0], it)
            reverseReader = ReversedLinesFileReader(file, Charsets.UTF_8)
        }

        postInvalidate()
    }

    private fun invalidateTextPaintAndMeasurements() {
        // Set up a default TextPaint object
        paint = Paint().apply {
            flags = Paint.ANTI_ALIAS_FLAG
            color = exampleColor
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 2f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // TODO: consider storing these as member variables to reduce
        // allocations per draw cycle.
        val paddingLeft = paddingLeft
        val paddingTop = paddingTop
        val paddingRight = paddingRight
        val paddingBottom = paddingBottom

        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom

        val maxVal = 1024f  // Maximum value of data sent

        // Read the last n lines in reverse order
        reverseReader?.readLines(contentWidth)?.forEachIndexed { i, value ->
            try {
                val v = value.trim().toFloat()
                val x = contentWidth.toFloat() - i  // plot in reverse since we're reading in reverse
                val y = contentHeight - ((v / maxVal) * contentHeight)
                canvas.drawPoint(x, y, paint)
            } catch(e: Exception) {
                //Log.e("ChartView", "Bad line: $value" )
            }
        }
    }
}