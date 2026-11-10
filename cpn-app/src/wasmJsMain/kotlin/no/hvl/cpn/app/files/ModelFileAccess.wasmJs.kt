package no.hvl.cpn.app.files

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

actual fun modelFileAccess(): ModelFileAccess = BrowserFileAccess

/** Browsers cannot write files: saving downloads the model, opening uses the file picker. */
private object BrowserFileAccess : ModelFileAccess {
    override val canSaveInPlace: Boolean = false

    override suspend fun open(): OpenedFile? =
        suspendCancellableCoroutine { continuation ->
            pickTextFile(
                onFile = { name, text -> if (continuation.isActive) continuation.resume(OpenedFile(name, text)) },
                onCancel = { if (continuation.isActive) continuation.resume(null) },
            )
        }

    override suspend fun save(
        text: String,
        suggestedName: String,
        target: String?,
    ): String {
        downloadText(suggestedName, text)
        return suggestedName
    }

    override suspend fun export(
        bytes: ByteArray,
        suggestedName: String,
    ): String {
        // Passed as one character per byte; the page turns the characters back into bytes.
        downloadBytes(suggestedName, CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString())
        return suggestedName
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(name, latin1) => {
        const bytes = new Uint8Array(latin1.length);
        for (let i = 0; i < latin1.length; i++) bytes[i] = latin1.charCodeAt(i);
        const url = URL.createObjectURL(new Blob([bytes], { type: 'application/octet-stream' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = name;
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }""",
)
private external fun downloadBytes(
    name: String,
    latin1: String,
)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(name, text) => {
        const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = name;
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }""",
)
private external fun downloadText(
    name: String,
    text: String,
)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """(onFile, onCancel) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.json,.cpn,application/json';
        input.onchange = () => {
            const file = input.files && input.files[0];
            if (!file) { onCancel(); return; }
            file.text().then((text) => onFile(file.name, text), () => onCancel());
        };
        input.oncancel = () => onCancel();
        input.click();
    }""",
)
private external fun pickTextFile(
    onFile: (String, String) -> Unit,
    onCancel: () -> Unit,
)
