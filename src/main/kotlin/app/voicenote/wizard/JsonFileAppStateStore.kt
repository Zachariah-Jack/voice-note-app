package app.voicenote.wizard

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class JsonFileAppStateStore(
    private val stateFile: Path,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) : AppStateStore {
    override fun load(): WizardAppState {
        if (!stateFile.exists()) {
            return WizardAppState()
        }

        return json.decodeFromString(stateFile.readText())
    }

    override fun save(state: WizardAppState) {
        stateFile.parent?.createDirectories()
        stateFile.writeText(json.encodeToString(state))
    }
}
