package app.phantom.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.random.Random

@Composable
fun MatrixRain(active: Boolean,modifier: Modifier=Modifier) {
    if(!active) return
    val clock=remember { mutableFloatStateOf(0f) }
    val seed=remember { Random(7163) }
    val speeds=remember { FloatArray(64) { 29f+seed.nextFloat()*45f } }
    val offsets=remember { FloatArray(64) { seed.nextFloat()*1600f } }
    val symbols=remember { "01アイウエカキクケサシスセタチツテナニヌネハヒフヘマミムメラリルレ".map(Char::toString) }
    val paint=remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.WHITE;typeface=Typeface.MONOSPACE } }
    LaunchedEffect(active) {
        var previous=0L
        while(true) {
            withFrameNanos { now->
                if(previous!=0L) clock.floatValue+=(now-previous).coerceAtMost(100_000_000L)/1_000_000_000f
                previous=now
            }
            delay(34) // Cap decorative animation near 24–30 fps; no timer when inactive.
        }
    }
    Canvas(modifier) {
        val cell=22f*density
        paint.textSize=13f*density
        val columns=(size.width/cell).toInt().coerceAtMost(64)
        val t=clock.floatValue
        for(col in 0 until columns) {
            val head=(offsets[col]+t*speeds[col]*density)%(size.height+cell*12)
            for(trail in 0..10) {
                val y=head-trail*cell
                if(y>0 && y<size.height) {
                    paint.alpha=if(trail==0) 130 else ((1f-trail/11f)*53).toInt()
                    val index=(floor(t*3).toInt()+col*17+trail*7).mod(symbols.size)
                    drawContext.canvas.nativeCanvas.drawText(symbols[index],col*cell+cell/3,y,paint)
                }
            }
        }
    }
}
