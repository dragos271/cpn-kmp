package no.hvl.cpn.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import no.hvl.cpn.core.InvalidNetException
import no.hvl.cpn.io.CpnDocumentFormat
import no.hvl.cpn.io.ModelFiles
import no.hvl.cpn.io.ModelFormatException

/** `cpn convert model --to cpn -o out.cpn`: writes a model in another format (T3.1b; the Python-like syntax comes with T2.8). */
class ConvertCommand(
    private val readFile: (String) -> ByteArray,
    private val writeFile: (String, ByteArray) -> Unit,
) : CliktCommand(name = "convert") {
    private val file by argument(help = "Model file: a CPN Tools .cpn file, a JSON model or an editable cpn-document")
    private val to by option("--to", help = "Format to write: cpn (CPN Tools 4) or document (the app's editable format)")
        .choice("cpn", "document")
        .required()
    private val output by option("--output", "-o", help = "File to write; standard output if omitted")

    override fun help(context: Context): String = "Convert a model to another format, e.g. to a .cpn file CPN Tools can open."

    override fun run() {
        val bytes =
            try {
                readFile(file)
            } catch (e: Exception) {
                throw CliktError("Cannot read '$file': ${e.message}", statusCode = 1)
            }
        val result =
            try {
                val model = ModelFiles.open(bytes, ModelFiles.modelName(file))
                when (to) {
                    "cpn" -> ModelFiles.exportCpn(model, model.document)
                    else -> CpnDocumentFormat.write(model.document).encodeToByteArray()
                }
            } catch (e: ModelFormatException) {
                throw CliktError("$file: ${e.message}", statusCode = 1)
            } catch (e: InvalidNetException) {
                throw CliktError("$file: ${e.message}", statusCode = 1)
            }
        val out = output
        if (out == null) {
            echo(result.decodeToString(), trailingNewline = false)
        } else {
            try {
                writeFile(out, result)
            } catch (e: Exception) {
                throw CliktError("Cannot write '$out': ${e.message}", statusCode = 1)
            }
            echo("Wrote $out")
        }
    }
}
