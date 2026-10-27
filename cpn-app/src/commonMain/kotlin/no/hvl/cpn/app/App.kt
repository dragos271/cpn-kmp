package no.hvl.cpn.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.hvl.cpn.app.resources.Res
import no.hvl.cpn.io.JsonNetFormat
import no.hvl.cpn.io.LoadedModel

/** The example shipped with the app: the simple protocol of Jensen & Kristensen (2009), ch. 2. */
const val EXAMPLE_MODEL: String = "files/simple-protocol.json"

/** Root of the app, shared by desktop and web. */
@Composable
fun App() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var model by remember { mutableStateOf<LoadedModel?>(null) }
            // Where the current model lives; the bundled example has no file, so its first Save asks.
            var location by remember { mutableStateOf<String?>(null) }
            var loadError by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                try {
                    model = JsonNetFormat.load(Res.readBytes(EXAMPLE_MODEL).decodeToString())
                } catch (e: Exception) {
                    loadError = "Couldn't load the example model: ${e.message}"
                }
            }
            val loaded = model
            when {
                loadError != null -> Text(loadError!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                loaded == null -> Text("Loading model…", Modifier.padding(24.dp))
                else ->
                    // A new model starts a fresh editor: history, selection and simulation belong to one model.
                    key(loaded) {
                        EditorScreen(loaded, location) { opened, openedFrom ->
                            location = openedFrom
                            model = opened
                        }
                    }
            }
        }
    }
}
