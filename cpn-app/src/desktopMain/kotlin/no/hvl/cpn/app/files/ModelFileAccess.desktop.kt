package no.hvl.cpn.app.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.hvl.cpn.io.xml.Xml
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual fun modelFileAccess(): ModelFileAccess = DesktopFileAccess

/**
 * The platform's native file dialogs (AWT FileDialog: the Finder dialog on macOS).
 *
 * A modal dialog shown on the UI thread runs a nested event loop, which would resume Compose's
 * coroutines (such as the click that opened the dialog) a second time and crash them. So dialogs are
 * shown, and files read and written, on a background thread; the UI thread keeps running normally.
 */
private object DesktopFileAccess : ModelFileAccess {
    override val canSaveInPlace: Boolean = true

    override suspend fun open(): OpenedFile? =
        withContext(Dispatchers.IO) {
            val dialog =
                FileDialog(null as Frame?, "Open model", FileDialog.LOAD).apply {
                    setFilenameFilter { _, name -> name.endsWith(".json") || name.endsWith(".cpn") }
                    isVisible = true
                }
            val name = dialog.file ?: return@withContext null
            val file = File(dialog.directory, name)
            // .cpn files declare ISO-8859-1; Xml.decode honours that and reads everything else as UTF-8.
            OpenedFile(file.absolutePath, Xml.decode(file.readBytes()))
        }

    override suspend fun save(
        text: String,
        suggestedName: String,
        target: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            val path =
                target ?: run {
                    val dialog =
                        FileDialog(null as Frame?, "Save model", FileDialog.SAVE).apply {
                            file = suggestedName
                            isVisible = true
                        }
                    val name = dialog.file ?: return@withContext null
                    File(dialog.directory, if (name.endsWith(".json")) name else "$name.json").absolutePath
                }
            File(path).writeText(text)
            path
        }

    override suspend fun export(
        bytes: ByteArray,
        suggestedName: String,
    ): String? =
        withContext(Dispatchers.IO) {
            val dialog =
                FileDialog(null as Frame?, "Export for CPN Tools", FileDialog.SAVE).apply {
                    file = suggestedName
                    isVisible = true
                }
            val name = dialog.file ?: return@withContext null
            val file = File(dialog.directory, if (name.endsWith(".cpn")) name else "$name.cpn")
            file.writeBytes(bytes)
            file.absolutePath
        }
}
