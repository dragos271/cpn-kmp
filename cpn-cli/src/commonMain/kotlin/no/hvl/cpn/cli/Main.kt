package no.hvl.cpn.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import no.hvl.cpn.api.Cpn

/** Root `cpn` command. More subcommands (`check`, `statespace`) come in T3.6. [readFile] and [writeFile] are replaced in tests. */
class CpnCommand(
    readFile: (String) -> ByteArray = ::readBytesFile,
    writeFile: (String, ByteArray) -> Unit = ::writeBytesFile,
) : CliktCommand(name = "cpn") {
    init {
        versionOption(Cpn.VERSION, names = setOf("--version", "-V"))
        subcommands(SimulateCommand(readFile), ConvertCommand(readFile, writeFile))
    }

    override val invokeWithoutSubcommand: Boolean = true

    override fun help(context: Context): String = "Headless Coloured Petri Net tool."

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echo("cpn ${Cpn.VERSION} (${Cpn.platform})")
            echo("Run 'cpn --help' for commands.")
        }
    }
}

internal fun readBytesFile(path: String): ByteArray = SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

internal fun writeBytesFile(
    path: String,
    bytes: ByteArray,
) = SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes) }

fun main(args: Array<String>) = CpnCommand().main(args)
