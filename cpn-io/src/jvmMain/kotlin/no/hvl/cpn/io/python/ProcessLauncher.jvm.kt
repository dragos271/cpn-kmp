package no.hvl.cpn.io.python

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

public actual fun platformLauncher(): ProcessLauncher? = JvmLauncher

internal actual fun pythonCommand(): String =
    System.getenv("CPN_PYTHON")?.takeIf { it.isNotBlank() }
        ?: if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "python" else "python3"

private object JvmLauncher : ProcessLauncher {
    override fun start(
        command: List<String>,
        directory: String?,
    ): LineChannel {
        val process =
            try {
                ProcessBuilder(command)
                    .directory(directory?.let(::File))
                    .start()
            } catch (e: IOException) {
                throw ProcessStartException("cannot start ${command.first()}: ${e.message}")
            }
        return JvmChannel(process)
    }
}

private class JvmChannel(
    private val process: Process,
) : LineChannel {
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
    private val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
    private val stderr = StringBuffer()
    private val hook = Thread { process.destroyForcibly() }

    init {
        // Standard error is read on its own thread, so a chatty script cannot block on a full pipe.
        Thread {
            process.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') }
        }.apply {
            isDaemon = true
            start()
        }
        Runtime.getRuntime().addShutdownHook(hook)
    }

    override fun send(line: String) {
        try {
            writer.write(line)
            writer.newLine()
            writer.flush()
        } catch (e: IOException) {
            // The process ended; receive() returns null and the caller reports standard error.
        }
    }

    override fun receive(): String? = reader.readLine()

    override fun errors(): String = stderr.toString()

    override fun close() {
        runCatching { writer.close() }
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}
