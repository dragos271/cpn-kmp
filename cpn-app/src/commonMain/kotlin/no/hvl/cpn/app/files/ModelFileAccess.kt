package no.hvl.cpn.app.files

/** A file the user picked: [name] is a path on desktop, a file name on the web. */
data class OpenedFile(
    val name: String,
    val text: String,
)

/** Opening and saving model files the way the platform does it: dialogs on desktop, upload and download on the web. */
interface ModelFileAccess {
    /** Whether [save] can write back to the file a model came from (desktop) or always hands out a new file (web). */
    val canSaveInPlace: Boolean

    /** Lets the user pick a model file; null if they cancel. */
    suspend fun open(): OpenedFile?

    /**
     * Saves [text]: to [target] when given (and [canSaveInPlace]), otherwise where the user chooses, starting
     * from [suggestedName]. Returns where it went, or null if the user cancelled.
     */
    suspend fun save(
        text: String,
        suggestedName: String,
        target: String?,
    ): String?
}

expect fun modelFileAccess(): ModelFileAccess

/** A file name from a model name: `Simple protocol` becomes `Simple protocol.json`. */
fun suggestedFileName(modelName: String): String {
    val base = modelName.filter { it.isLetterOrDigit() || it in " -_." }.trim().ifEmpty { "model" }
    return if (base.endsWith(".json")) base else "$base.json"
}
