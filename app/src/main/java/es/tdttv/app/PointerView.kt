package es.tdttv.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Flecha del modo "cursor y teclado". Solo dibuja; el movimiento lo calcula PointerMath. */
class PointerView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 22f)
        lineTo(5.5f, 17f)
        lineTo(9.5f, 26f)
        lineTo(13f, 24.5f)
        lineTo(9f, 15.5f)
        lineTo(16f, 15.5f)
        close()
    }
    private var px = 0f
    private var py = 0f

    init {
        isFocusable = false
        isClickable = false
    }

    fun moveTo(x: Float, y: Float) {
        px = x
        py = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = 1.5f * density
        canvas.save()
        canvas.translate(px, py)
        canvas.scale(s, s)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, edge)
        canvas.restore()
    }
}
