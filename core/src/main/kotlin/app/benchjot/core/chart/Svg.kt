package app.benchjot.core.chart

import kotlin.math.cos
import kotlin.math.sin

/** The scene as a standalone SVG (the SVG download of a natively drawn chart). */
fun toSvg(scene: Scene, background: Int? = null): String {
    val sb = StringBuilder()
    fun n(v: Float) = String.format(java.util.Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    fun c(argb: Int) = String.format("#%06x", argb and 0xffffff)
    fun a(argb: Int) = ((argb ushr 24) / 255f).let { if (it >= 0.999f) "" else " fill-opacity=\"${n(it)}\"" }
    fun sa(argb: Int) = ((argb ushr 24) / 255f).let { if (it >= 0.999f) "" else " stroke-opacity=\"${n(it)}\"" }
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${n(scene.width)}\" height=\"${n(scene.height)}\" viewBox=\"0 0 ${n(scene.width)} ${n(scene.height)}\" font-family=\"sans-serif\">\n")
    sb.append("<defs><clipPath id=\"plot\"><rect x=\"${n(scene.plot.l)}\" y=\"${n(scene.plot.t)}\" width=\"${n(scene.plot.w)}\" height=\"${n(scene.plot.h)}\"/></clipPath></defs>\n")
    background?.let { sb.append("<rect width=\"100%\" height=\"100%\" fill=\"${c(it)}\"${a(it)}/>\n") }
    var clipped = false
    for (op in scene.ops) {
        when (op) {
            is Op.Clip -> { sb.append("<g clip-path=\"url(#plot)\">\n"); clipped = true }
            Op.Unclip -> if (clipped) { sb.append("</g>\n"); clipped = false }
            is Op.Line -> {
                val pts = op.pts.toList().chunked(2).joinToString(" ") { "${n(it[0])},${n(it[1])}" }
                sb.append("<polyline points=\"$pts\" fill=\"none\" stroke=\"${c(op.color)}\"${sa(op.color)} stroke-width=\"${n(op.width)}\" stroke-linejoin=\"round\"/>\n")
            }
            is Op.Fill -> {
                val pts = op.pts.toList().chunked(2).joinToString(" ") { "${n(it[0])},${n(it[1])}" }
                sb.append("<polygon points=\"$pts\" fill=\"${c(op.color)}\"${a(op.color)}/>\n")
            }
            is Op.Rect -> {
                val stroke = op.stroke?.let { " stroke=\"${c(it)}\"" } ?: ""
                val r = if (op.radius > 0) " rx=\"${n(op.radius)}\"" else ""
                sb.append("<rect x=\"${n(op.l)}\" y=\"${n(op.t)}\" width=\"${n(op.r - op.l)}\" height=\"${n(op.b - op.t)}\"$r fill=\"${c(op.color)}\"${a(op.color)}$stroke/>\n")
            }
            is Op.Circle -> sb.append("<circle cx=\"${n(op.x)}\" cy=\"${n(op.y)}\" r=\"${n(op.r)}\" fill=\"${c(op.color)}\"${a(op.color)}/>\n")
            is Op.Text -> {
                val anchor = when (op.align) { Align.Left -> "start"; Align.Center -> "middle"; Align.Right -> "end" }
                val rot = if (op.rotate) " transform=\"rotate(-90 ${n(op.x)} ${n(op.y)})\"" else ""
                val bold = if (op.bold) " font-weight=\"bold\"" else ""
                sb.append("<text x=\"${n(op.x)}\" y=\"${n(op.y)}\" font-size=\"${n(op.size)}\" fill=\"${c(op.color)}\" text-anchor=\"$anchor\"$bold$rot>${esc(op.text)}</text>\n")
            }
            is Op.Wedge -> {
                fun pt(deg: Float, r: Float): String {
                    val rad = Math.toRadians((deg - 90).toDouble())
                    return "${n(op.cx + r * cos(rad).toFloat())},${n(op.cy + r * sin(rad).toFloat())}"
                }
                val large = if (op.sweep > 180) 1 else 0
                val end = op.start + op.sweep.coerceAtMost(359.99f)
                val d = if (op.hole > 0) "M${pt(op.start, op.r)} A${n(op.r)},${n(op.r)} 0 $large 1 ${pt(end, op.r)} L${pt(end, op.hole)} A${n(op.hole)},${n(op.hole)} 0 $large 0 ${pt(op.start, op.hole)} Z"
                else "M${n(op.cx)},${n(op.cy)} L${pt(op.start, op.r)} A${n(op.r)},${n(op.r)} 0 $large 1 ${pt(end, op.r)} Z"
                val gap = op.gap?.let { " stroke=\"${c(it)}\" stroke-width=\"1\"" } ?: ""
                sb.append("<path d=\"$d\" fill=\"${c(op.color)}\"${a(op.color)}$gap/>\n")
            }
        }
    }
    if (clipped) sb.append("</g>\n")
    sb.append("</svg>\n")
    return sb.toString()
}
