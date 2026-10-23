package no.hvl.cpn.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.versionOption
import no.hvl.cpn.api.Cpn

/** Root `cpn` command. Subcommands (`simulate`, `statespace`, ...) are added in T1.8 and T3.6. */
class CpnCommand : CliktCommand(name = "cpn") {
    init {
        versionOption(Cpn.VERSION, names = setOf("--version", "-V"))
    }

    override fun help(context: Context): String = "Headless Coloured Petri Net tool."

    override fun run() {
        echo("cpn ${Cpn.VERSION} (${Cpn.platform})")
    }
}

fun main(args: Array<String>) = CpnCommand().main(args)
