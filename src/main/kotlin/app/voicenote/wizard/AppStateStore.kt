package app.voicenote.wizard

interface AppStateStore {
    fun load(): WizardAppState

    fun save(state: WizardAppState)
}
