package com.diogu.airconditioner

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Code-drawn dial: no bitmap assets, fonts, or UI toolkit dependencies. */
class ThermostatView(context: Context) : View(context) {
    var target = 22.0
    var minimum = 10.0
    var maximum = 31.0
    var accent = Color.rgb(205,232,183)
    var mode = "HEAT"
    var powered = true
    var available = true
    private val oval = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f + 5f
        val r = minOf(width * .39f,height * .43f)
        val stroke = resources.displayMetrics.density * 8
        val progress = ((target - minimum) / (maximum-minimum).coerceAtLeast(1.0)).coerceIn(0.0,1.0).toFloat()
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = stroke; paint.color = Color.rgb(42,61,60)
        oval.set(cx-r,cy-r,cx+r,cy+r)
        canvas.drawArc(oval,150f,240f,false,paint)
        paint.color = if(powered) accent else Color.rgb(114,135,136)
        canvas.drawArc(oval,150f,240f * progress,false,paint)
        paint.strokeWidth = resources.displayMetrics.density
        for(i in 0..40) {
            val angle = Math.toRadians((150.0+i*6))
            val inner = r + stroke * 1.6f; val outer = inner + if(i%5 == 0) stroke else stroke*.45f
            paint.color = if(i/40f <= progress && powered) accent else Color.rgb(57,77,76)
            canvas.drawLine(cx+cos(angle).toFloat()*inner,cy+sin(angle).toFloat()*inner,cx+cos(angle).toFloat()*outer,cy+sin(angle).toFloat()*outer,paint)
        }
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(152,175,172); paint.typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL); paint.textSize = r*.12f
        canvas.drawText("TARGET TEMPERATURE",cx,cy-r*.44f,paint)
        paint.color = Color.rgb(241,247,237); paint.typeface = Typeface.create("sans-serif-condensed-light",Typeface.NORMAL); paint.textSize = r*.70f
        val label = if(!available) "—" else if(target%1 == 0.0) "${target.toInt()}°" else String.format(java.util.Locale.getDefault(),"%.1f°",target)
        canvas.drawText(label,cx,cy+r*.23f,paint)
        paint.textSize = r*.135f; paint.color = if(powered) accent else Color.rgb(152,175,172); paint.typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
        canvas.drawText(if(powered) mode.uppercase(java.util.Locale.ROOT) else "POWERED OFF",cx,cy+r*.51f,paint)
        contentDescription = "Target $label Celsius. ${if(powered) mode else "Powered off"}"
    }
}
