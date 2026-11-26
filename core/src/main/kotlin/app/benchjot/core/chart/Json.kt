package app.benchjot.core.chart

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// Small readers for chart specs: dotted lookups, values as numbers or strings, and the
// typed arrays libraries write for numpy data (Plotly's {dtype, bdata}, Bokeh's ndarray).

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.let { p -> if (p.isString) p.content.toDoubleOrNull() else p.doubleOrNull }
internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
internal fun JsonElement?.path(vararg keys: String): JsonElement? = keys.fold(this) { e, k -> e[k] }

/** A scalar as text: strings as is, numbers as Plotly prints them, null as "". */
internal fun JsonElement?.text(): String = when (this) {
    null, is JsonNull -> ""
    is JsonPrimitive -> if (isString) content else doubleOrNull?.let(::fmt) ?: content
    else -> toString()
}

/** A title that may be a string or {text: ...}. */
internal fun JsonElement?.titleText(): String = when (this) {
    is JsonPrimitive -> if (isString) content else ""
    is JsonObject -> this["text"].str().orEmpty()
    else -> ""
}

/** Decodes a typed-array blob (little-endian) into doubles. dtype: numpy-style (f8, i4, u1, float64, int32, ...). */
internal fun decodeTyped(dtype: String, bytes: ByteArray): DoubleArray {
    val d = dtype.lowercase().removePrefix("<")
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun n(size: Int) = bytes.size / size
    return when (d) {
        "f8", "float64" -> DoubleArray(n(8)) { buf.getDouble(it * 8) }
        "f4", "float32" -> DoubleArray(n(4)) { buf.getFloat(it * 4).toDouble() }
        "i1", "int8" -> DoubleArray(bytes.size) { bytes[it].toDouble() }
        "u1", "uint8", "bool" -> DoubleArray(bytes.size) { (bytes[it].toInt() and 0xff).toDouble() }
        "i2", "int16" -> DoubleArray(n(2)) { buf.getShort(it * 2).toDouble() }
        "u2", "uint16" -> DoubleArray(n(2)) { (buf.getShort(it * 2).toInt() and 0xffff).toDouble() }
        "i4", "int32" -> DoubleArray(n(4)) { buf.getInt(it * 4).toDouble() }
        "u4", "uint32" -> DoubleArray(n(4)) { (buf.getInt(it * 4).toLong() and 0xffffffffL).toDouble() }
        "i8", "int64" -> DoubleArray(n(8)) { buf.getLong(it * 8).toDouble() }
        "u8", "uint64" -> DoubleArray(n(8)) { buf.getLong(it * 8).toULong().toDouble() }
        else -> throw UnsupportedChart("numpy $dtype arrays")
    }
}

internal fun base64(s: String): ByteArray {
    val raw = Base64.getDecoder().decode(s)
    // Bokeh 3.10+ gzips large buffers (magic 1f 8b).
    return if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(raw)).readBytes() else raw
}

/** A spec's array as JSON values: a plain list, Plotly's {dtype, bdata, shape} or Bokeh's ndarray. Rows for 2-D. */
internal fun values(e: JsonElement?): List<JsonElement> {
    when (e) {
        null, is JsonNull -> return emptyList()
        is JsonArray -> return e
        is JsonObject -> {
            val typed = when {
                e["bdata"] != null -> decodeTyped(e["dtype"].str() ?: "f8", base64(e["bdata"].str()!!)) to e["shape"]
                e["type"].str() == "ndarray" && e.path("array", "type").str() == "bytes" ->
                    decodeTyped(e["dtype"].str() ?: "float64", base64(e.path("array", "data").str()!!)) to e["shape"]
                e["type"].str() == "ndarray" -> return values(e["array"])
                else -> null
            } ?: return emptyList()
            val (flat, shape) = typed
            val dims = when (shape) {
                is JsonPrimitive -> shape.content.split(",").mapNotNull { it.trim().toIntOrNull() }
                is JsonArray -> shape.mapNotNull { it.num()?.toInt() }
                else -> listOf(flat.size)
            }
            if (dims.size == 2) {
                val (rows, cols) = dims
                return List(rows) { r -> JsonArray(List(cols) { c -> JsonPrimitive(flat[r * cols + c]) }) }
            }
            return flat.map { JsonPrimitive(it) }
        }
        else -> return listOf(e)
    }
}

internal fun numbers(e: JsonElement?): List<Double> = values(e).map { it.num() ?: Double.NaN }
