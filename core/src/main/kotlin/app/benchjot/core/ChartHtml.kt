package app.benchjot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// A chart as one self-contained HTML page (the "HTML (interactive)" download), drawn by the chart's
// own library from its CDN with the spec inlined, so a Plotly animation keeps its play button and
// slider, a 3D plot still rotates, and Vega-Lite / Bokeh charts keep their tooltips. The web's
// src/lib/run/chart-html.ts, line for line (same scripts and versions).

const val PLOTLY_SCRIPT = "https://cdn.plot.ly/plotly-4.1.1.min.js"
val VEGA_SCRIPTS = listOf("https://cdn.jsdelivr.net/npm/vega@6", "https://cdn.jsdelivr.net/npm/vega-lite@6", "https://cdn.jsdelivr.net/npm/vega-embed@7")
fun bokehScript(version: String) = "https://cdn.bokeh.org/bokeh/release/bokeh-${version.replace(Regex("[^\\d.]"), "")}.min.js"

private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** JSON safe inside a <script>: no "</script>" or "<!--" can end or confuse the element. Throws if it isn't JSON. */
private fun inline(specJson: String): String =
    Json.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(specJson))
        .replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

/** The page: [specJson] is the chart's stored spec (its .json file) as text. */
fun chartHtml(lib: String, version: String, specJson: String, title: String = ""): String {
    val (scripts, code) = when (lib) {
        "plotly" -> listOf(PLOTLY_SCRIPT) to "Plotly.newPlot(el, { data: spec.data || [], layout: spec.layout || {}, frames: spec.frames || [], config: { responsive: true, displaylogo: false } });"
        "vega-lite" -> VEGA_SCRIPTS to "vegaEmbed(el, spec, { actions: false });"
        "bokeh" -> listOf(bokehScript(version)) to "Bokeh.embed.embed_item(spec, \"chart\");"
        else -> error("Not a chart library: $lib")
    }
    return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title.ifEmpty { "Chart" })}</title>
<style>html,body{margin:0;height:100%;font-family:system-ui,sans-serif}#chart{width:100%;height:100vh}</style>
${scripts.joinToString("\n") { "<script src=\"${escapeHtml(it)}\"></script>" }}
</head>
<body>
<div id="chart"></div>
<script>
const spec = ${inline(specJson)};
const el = document.getElementById("chart");
$code
</script>
</body>
</html>
"""
}
