package com.example.rightposture.core.rpose

/**
 * Minimal JSON reader/writer, just enough for the §9 bundle.
 *
 * Deliberately not a dependency. `org.json` ships with Android but is a throwing stub under JVM unit
 * tests, so the round-trip test — the whole point of 3.9 — could not run against it. Adding
 * kotlinx-serialization would mean a second Kotlin plugin on a build that already breaks for
 * environmental reasons. The §9 schema is ours, closed, and made of objects, arrays, numbers,
 * strings and booleans, so the small parser below covers all of it.
 *
 * Values are plain Kotlin: [Map] (string-keyed, insertion-ordered), [List], [String], [Double],
 * [Boolean], null. Every number parses as a Double; callers narrow with [int] and [intOrNull].
 */

fun jsonWrite(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

private fun write(value: Any?, out: StringBuilder) {
    when (value) {
        null -> out.append("null")
        is Boolean -> out.append(value)
        is Int, is Long -> out.append(value.toString())
        is Number -> out.append(finite(value))
        is String -> writeString(value, out)
        is Map<*, *> -> {
            out.append('{')
            value.entries.forEachIndexed { index, entry ->
                if (index > 0) out.append(',')
                writeString(entry.key.toString(), out)
                out.append(':')
                write(entry.value, out)
            }
            out.append('}')
        }
        is List<*> -> {
            out.append('[')
            value.forEachIndexed { index, entry ->
                if (index > 0) out.append(',')
                write(entry, out)
            }
            out.append(']')
        }
        else -> throw IllegalArgumentException("Not a JSON value: " + value::class)
    }
}

/**
 * JSON has no NaN or Infinity. §6.22 treats both as invalid data, not as something to encode, so
 * refuse here rather than emit a token no parser will read back.
 */
private fun finite(value: Number): String {
    val d = value.toDouble()
    require(d.isFinite()) { "Cannot serialise non-finite number: " + value }
    return d.toString()
}

private fun writeString(value: String, out: StringBuilder) {
    out.append('"')
    value.forEach { c ->
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u" + c.code.toString(16).padStart(4, '0'))
            else -> out.append(c)
        }
    }
    out.append('"')
}

fun jsonParse(text: String): Any? {
    val parser = JsonParser(text)
    val value = parser.value()
    parser.skipWhitespace()
    require(parser.done) { "Trailing content at offset " + parser.offset }
    return value
}

private class JsonParser(private val text: String) {
    var offset = 0
    val done get() = offset >= text.length

    fun skipWhitespace() {
        while (offset < text.length && text[offset].isWhitespace()) offset++
    }

    fun value(): Any? {
        skipWhitespace()
        require(!done) { "Unexpected end of input" }
        val c = text[offset]
        return when {
            c == '{' -> obj()
            c == '[' -> array()
            c == '"' -> string()
            c == 't' -> literal("true", true)
            c == 'f' -> literal("false", false)
            c == 'n' -> literal("null", null)
            c == '-' || c in '0'..'9' -> number()
            else -> fail("Unexpected '" + c + "'")
        }
    }

    private fun obj(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        offset++
        skipWhitespace()
        if (peek() == '}') {
            offset++
            return map
        }
        while (true) {
            skipWhitespace()
            val key = string()
            skipWhitespace()
            expect(':')
            map[key] = value()
            skipWhitespace()
            if (expectOneOf(',', '}') == '}') return map
        }
    }

    private fun array(): List<Any?> {
        val list = mutableListOf<Any?>()
        offset++
        skipWhitespace()
        if (peek() == ']') {
            offset++
            return list
        }
        while (true) {
            list += value()
            skipWhitespace()
            if (expectOneOf(',', ']') == ']') return list
        }
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            require(!done) { "Unterminated string" }
            val c = text[offset++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    require(!done) { "Unterminated escape" }
                    when (val e = text[offset++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append(12.toChar())
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            out.append(text.substring(offset, offset + 4).toInt(16).toChar())
                            offset += 4
                        }
                        else -> fail("Bad escape")
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun number(): Double {
        val start = offset
        if (peek() == '-') offset++
        while (!done && (text[offset] in '0'..'9' || text[offset] in ".eE+-")) offset++
        return text.substring(start, offset).toDoubleOrNull() ?: fail("Bad number at " + start)
    }

    private fun <T> literal(token: String, value: T): T {
        require(text.startsWith(token, offset)) { "Expected " + token + " at " + offset }
        offset += token.length
        return value
    }

    private fun peek(): Char? = if (done) null else text[offset]

    private fun expect(c: Char) {
        require(peek() == c) { "Expected '" + c + "' at " + offset }
        offset++
    }

    private fun expectOneOf(a: Char, b: Char): Char {
        val c = peek() ?: fail("Unexpected end of input")
        require(c == a || c == b) { "Expected '" + a + "' or '" + b + "' at " + offset }
        offset++
        return c
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message + " in JSON")
}

// Typed readers. Each names the field it failed on: "expected a number, got null" three files deep
// inside a zip is not something a caller can act on.

@Suppress("UNCHECKED_CAST")
fun Any?.asObject(field: String): Map<String, Any?> =
    (this as? Map<String, Any?>) ?: throw IllegalArgumentException(field + " is not an object")

fun Map<String, Any?>.obj(field: String): Map<String, Any?> = this[field].asObject(field)

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(field: String): List<Any?> =
    (this[field] as? List<Any?>) ?: throw IllegalArgumentException(field + " is not an array")

fun Map<String, Any?>.str(field: String): String =
    (this[field] as? String) ?: throw IllegalArgumentException(field + " is not a string")

fun Map<String, Any?>.strOrNull(field: String): String? = this[field] as? String

fun Map<String, Any?>.num(field: String): Double =
    (this[field] as? Double) ?: throw IllegalArgumentException(field + " is not a number")

fun Map<String, Any?>.numOrNull(field: String): Double? = this[field] as? Double

fun Map<String, Any?>.int(field: String): Int = num(field).toInt()

fun Map<String, Any?>.intOrNull(field: String): Int? = numOrNull(field)?.toInt()

fun Map<String, Any?>.bool(field: String): Boolean =
    (this[field] as? Boolean) ?: throw IllegalArgumentException(field + " is not a boolean")

fun Map<String, Any?>.floats(field: String): List<Float> = list(field).map {
    (it as? Double)?.toFloat() ?: throw IllegalArgumentException(field + " contains a non-number")
}

fun Map<String, Any?>.strings(field: String): List<String> = list(field).map {
    (it as? String) ?: throw IllegalArgumentException(field + " contains a non-string")
}
