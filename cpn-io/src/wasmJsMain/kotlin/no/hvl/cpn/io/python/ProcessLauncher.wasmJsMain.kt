package no.hvl.cpn.io.python

/** Not available on this platform: models use the CPN ML fallback bodies of external functions. */
public actual fun platformLauncher(): ProcessLauncher? = null

internal actual fun pythonCommand(): String = "python3"
