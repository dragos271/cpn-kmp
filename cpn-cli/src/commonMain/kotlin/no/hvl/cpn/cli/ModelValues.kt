package no.hvl.cpn.cli

import com.github.ajalt.clikt.core.CliktError

/**
 * `--set NAME=VALUE`: replaces the value of a `val NAME = ...;` declaration before the model is
 * read, so one file can be run with 2, 3 or 4 workers without copying it. Works on `.cpn` files
 * and on JSON documents alike, since both keep the declaration as text.
 *
 * The bytes are handled as ISO-8859-1, which maps every byte to one character and back, so the rest
 * of the file is unchanged whatever its encoding.
 */
internal fun setValues(
    bytes: ByteArray,
    values: List<Pair<String, String>>,
    file: String,
): ByteArray {
    if (values.isEmpty()) return bytes
    var text = CharArray(bytes.size) { (bytes[it].toInt() and 0xff).toChar() }.concatToString()
    for ((name, value) in values) {
        // In a JSON document a line break is the two characters `\n`, so `val` may follow an `n`.
        val declaration = Regex("""(?:(?<=\\[nrt])|(?<![\w']))val\s+${Regex.escape(name)}\s*=\s*[^;]*;""")
        // A .cpn file may keep a declaration twice, as text and in its <layout>: both are replaced.
        if (!declaration.containsMatchIn(text)) {
            throw CliktError("$file: --set $name: the model has no 'val $name = ...;' declaration", statusCode = 1)
        }
        text = declaration.replace(text, Regex.escapeReplacement("val $name = $value;"))
    }
    return ByteArray(text.length) {
        val c = text[it].code
        require(c <= 0xff) { "--set values must be ISO-8859-1 text" }
        c.toByte()
    }
}

/** Parses `NAME=VALUE`. */
internal fun parseSet(option: String): Pair<String, String> {
    val name = option.substringBefore('=', "").trim()
    val value = option.substringAfter('=', "").trim()
    if (name.isEmpty() || value.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' || it == '\'' }) {
        throw CliktError("--set expects NAME=VALUE, for example --set W=3; got '$option'", statusCode = 1)
    }
    return name to value
}
