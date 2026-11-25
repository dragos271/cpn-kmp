package no.hvl.cpn.cli

import com.github.ajalt.clikt.core.CliktError
import no.hvl.cpn.api.perf.ModelValues
import no.hvl.cpn.api.perf.StudyException

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
        text =
            try {
                ModelValues.set(text, name, value)
            } catch (e: StudyException) {
                throw CliktError("$file: --set $name: ${e.message}", statusCode = 1)
            }
    }
    return ByteArray(text.length) {
        val c = text[it].code
        require(c <= 0xff) { "--set values must be ISO-8859-1 text" }
        c.toByte()
    }
}

/** Parses `NAME=VALUE`. */
internal fun parseSet(option: String): Pair<String, String> =
    try {
        ModelValues.parseAssignment(option)
    } catch (e: StudyException) {
        throw CliktError("--set: ${e.message}", statusCode = 1)
    }

/** Checks a model's Python functions (F13) before it runs, so a missing file or function is reported up front. */
internal fun checkPython(
    python: no.hvl.cpn.io.python.PythonFunctions?,
    file: String,
) {
    try {
        python?.check()
    } catch (e: no.hvl.cpn.lang.eval.ExternalFunctionException) {
        throw CliktError("$file: ${e.message}", statusCode = 1)
    }
}
