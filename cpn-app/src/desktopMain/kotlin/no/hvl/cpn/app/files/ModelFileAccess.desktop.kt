package no.hvl.cpn.app.files

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual fun modelFileAccess(): ModelFileAccess = DesktopFileAccess

/** The platform's native file dialogs (AWT FileDialog: the Finder dialog on macOS). */
private object DesktopFileAccess : ModelFileAccess {
    override val canSaveInPlace: Boolean = true

    override suspend fun open(): OpenedFile? {
        val dialog =
            FileDialog(null as Frame?, "Open model", FileDialog.LOAD).apply {
                setFilenameFilter { _, name -> name.endsWith(".json") }
                isVisible = true
            }
        val name = dialog.file ?: return null
        val file = File(dialog.directory, name)
        return OpenedFile(file.absolutePath, file.readText())
    }

    override suspend fun save(
        text: String,
        suggestedName: String,
        target: String?,
    ): String? {
        val path =
            target ?: run {
                val dialog =
                    FileDialog(null as Frame?, "Save model", FileDialog.SAVE).apply {
                        file = suggestedName
                        isVisible = true
                    }
                val name = dialog.file ?: return null
                File(dialog.directory, if (name.endsWith(".json")) name else "$name.json").absolutePath
            }
        File(path).writeText(text)
        return path
    }
}
