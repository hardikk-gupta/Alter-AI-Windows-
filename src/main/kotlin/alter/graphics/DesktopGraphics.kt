package alter.graphics

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

// Small platform adapter for the upstream renderer; geometry remains in KoiAsciiRenderer.
class Bitmap private constructor(val image: BufferedImage) {
    enum class Config { ARGB_8888 }
    val width get() = image.width
    val height get() = image.height
    fun recycle() = image.flush()
    fun eraseColor(color: Int) {
        image.createGraphics().apply {
            composite = AlphaComposite.Src; this.color = java.awt.Color(color, true)
            fillRect(0, 0, width, height); dispose()
        }
    }
    fun getPixels(out: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        image.getRGB(x, y, w, h, out, offset, stride)
    }
    companion object {
        fun createBitmap(w: Int, h: Int, config: Config) = Bitmap(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB))
    }
}
object Colors {
    const val TRANSPARENT = 0
    fun rgb(r: Int, g: Int, b: Int) = java.awt.Color(r, g, b).rgb
}
object Typeface { val MONOSPACE: Font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
class DashPathEffect(val intervals: FloatArray, val phase: Float)
class Paint(flags: Int = 0) {
    enum class Style { STROKE, FILL }
    enum class Align { CENTER }
    var color: Int = 0xff000000.toInt()
        set(value) { field = value; alpha = value ushr 24 }
    var alpha = 255
    var strokeWidth = 1f
    var style = Style.FILL
    var pathEffect: DashPathEffect? = null
    var typeface: Font = Typeface.MONOSPACE
    var textAlign = Align.CENTER
    var textSize = 12f
    data class Metrics(val ascent: Float, val descent: Float)
    val fontMetrics get() = Metrics(-textSize * .8f, textSize * .2f)
    companion object { const val ANTI_ALIAS_FLAG = 1 }
}
class Path {
    val shape = Path2D.Float()
    fun reset() = shape.reset()
    fun moveTo(x: Float, y: Float) = shape.moveTo(x.toDouble(), y.toDouble())
    fun lineTo(x: Float, y: Float) = shape.lineTo(x.toDouble(), y.toDouble())
    fun close() = shape.closePath()
}
class Canvas private constructor(private var g: Graphics2D) : AutoCloseable {
    constructor(bitmap: Bitmap) : this(bitmap.image.createGraphics())
    private val stack = ArrayDeque<Graphics2D>()
    init { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON) }
    fun save() { stack.addLast(g); g = g.create() as Graphics2D }
    fun restore() { g.dispose(); g = stack.removeLast() }
    fun scale(x: Float, y: Float) = g.scale(x.toDouble(), y.toDouble())
    fun translate(x: Float, y: Float) = g.translate(x.toDouble(), y.toDouble())
    fun rotate(degrees: Float) = g.rotate(Math.toRadians(degrees.toDouble()))
    fun clipPath(p: Path) = g.clip(p.shape)
    private fun apply(p: Paint) {
        g.color = java.awt.Color((p.color and 0xffffff) or (p.alpha.coerceIn(0, 255) shl 24), true)
        g.stroke = p.pathEffect?.let { BasicStroke(p.strokeWidth, BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_ROUND, 10f, it.intervals, it.phase) } ?: BasicStroke(p.strokeWidth)
    }
    fun drawPath(path: Path, p: Paint) { apply(p); if (p.style == Paint.Style.STROKE) g.draw(path.shape) else g.fill(path.shape) }
    fun drawCircle(x: Float, y: Float, radius: Float, p: Paint) {
        apply(p); val shape = Ellipse2D.Float(x-radius, y-radius, radius*2, radius*2)
        if (p.style == Paint.Style.STROKE) g.draw(shape) else g.fill(shape)
    }
    fun drawOval(l: Float, t: Float, r: Float, b: Float, p: Paint) { apply(p); g.fill(Ellipse2D.Float(l,t,r-l,b-t)) }
    fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) { apply(p); g.fill(Rectangle2D.Float(l,t,r-l,b-t)) }
    fun drawLine(x: Float, y: Float, x2: Float, y2: Float, p: Paint) { apply(p); g.draw(Line2D.Float(x,y,x2,y2)) }
    fun drawText(s: String, start: Int, end: Int, x: Float, y: Float, p: Paint) = drawText(s.substring(start,end),x,y,p)
    fun drawText(s: String, x: Float, y: Float, p: Paint) {
        apply(p); g.font = p.typeface.deriveFont(p.textSize)
        g.drawString(s, x - g.fontMetrics.stringWidth(s) / 2f, y)
    }
    override fun close() { while (stack.isNotEmpty()) restore(); g.dispose() }
}
