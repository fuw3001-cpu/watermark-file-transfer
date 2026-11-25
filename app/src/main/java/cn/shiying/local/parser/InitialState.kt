package cn.shiying.local.parser

/** Reads a JSON-like assignment without executing page JavaScript. Quoted text stays unchanged. */
object InitialState {
    fun extract(script: String): String? {
        val match = Regex("window\\.__INITIAL_STATE__\\s*=\\s*").find(script) ?: return null
        val start = script.indexOf('{', match.range.last + 1)
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until script.length) {
            val c = script[index]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return normalize(script.substring(start, index + 1)) }
            }
        }
        return null
    }
    private fun normalize(text: String): String = buildString {
        var index = 0
        var quoted = false
        var escaped = false
        while (index < text.length) {
            val c = text[index]
            if (quoted) {
                append(c); index++
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else if (c == '"') { quoted = true; append(c); index++
            } else if (c.isLetter() || c == '_') {
                val begin = index
                while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
                val token = text.substring(begin, index)
                val constructor = if (token == "new") Regex("\\s+(Map|Set|Date)\\s*\\(").find(text, index)?.takeIf { it.range.first == index } else null
                if (constructor != null) {
                    var end = constructor.range.last + 1
                    var depth = 1
                    var inString = false
                    var slash = false
                    while (end < text.length && depth > 0) {
                        val value = text[end++]
                        if (inString) {
                            if (slash) slash = false else if (value == '\\') slash = true else if (value == '"') inString = false
                        } else when (value) { '"' -> inString = true; '(' -> depth++; ')' -> depth-- }
                    }
                    require(depth == 0) { "小红书页面状态不完整" }
                    append(when (constructor.groupValues[1]) { "Map" -> "{}"; "Set" -> "[]"; else -> "null" })
                    index = end
                } else if (token == "undefined" || token == "NaN") append("null") else append(token)
            } else { append(c); index++ }
        }
    }
}
