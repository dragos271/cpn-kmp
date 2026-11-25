package no.hvl.cpn.io.python

/** A running process that reads and writes lines of text (F13: the Python process). */
public interface LineChannel {
    public fun send(line: String)

    /** The next line the process writes, or null if it has ended. */
    public fun receive(): String?

    /** What the process wrote to standard error so far (for error messages). */
    public fun errors(): String

    public fun close()
}

/** Starts processes; only where the platform can (the JVM: the command line and the desktop app). */
public fun interface ProcessLauncher {
    /**
     * Starts [command] in [directory] (null: the current directory).
     * @throws ProcessStartException if it cannot be started, e.g. Python is not installed.
     */
    public fun start(
        command: List<String>,
        directory: String?,
    ): LineChannel
}

public class ProcessStartException(
    message: String,
) : RuntimeException(message)

/** This platform's launcher, or null where processes cannot be started (browser, Node, native for now). */
public expect fun platformLauncher(): ProcessLauncher?

/** The Python command: `CPN_PYTHON` if set, otherwise `python3` (`python` on Windows). */
internal expect fun pythonCommand(): String
