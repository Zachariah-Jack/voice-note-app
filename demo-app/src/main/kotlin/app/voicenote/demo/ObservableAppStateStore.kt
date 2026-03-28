package app.voicenote.demo

import app.voicenote.wizard.AppStateStore
import app.voicenote.wizard.WizardAppState

class ObservableAppStateStore(
    private val delegate: AppStateStore,
    private val onSave: (WizardAppState) -> Unit,
) : AppStateStore {
    override fun load(): WizardAppState = delegate.load()

    override fun save(state: WizardAppState) {
        delegate.save(state)
        onSave(state)
    }
}
