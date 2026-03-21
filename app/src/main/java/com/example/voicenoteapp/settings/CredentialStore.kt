package com.example.voicenoteapp.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.assistantSettingsDataStore by preferencesDataStore(name = "assistant_settings")

class CredentialStore(context: Context) {
    private val appContext = context.applicationContext

    val settings: Flow<AssistantSettings> = appContext.assistantSettingsDataStore.data.map { prefs ->
        AssistantSettings(
            openAiApiKey = prefs[OPENAI_API_KEY].orEmpty(),
            openAiModel = prefs[OPENAI_MODEL].orEmpty(),
            jobTreadBaseUrl = prefs[JOBTREAD_BASE_URL].orEmpty(),
            jobTreadApiKey = prefs[JOBTREAD_API_KEY].orEmpty(),
            jobTreadOrganizationId = prefs[JOBTREAD_ORGANIZATION_ID].orEmpty(),
            jobTreadOrganizationName = prefs[JOBTREAD_ORGANIZATION_NAME].orEmpty()
        )
    }

    suspend fun save(updated: AssistantSettings) {
        appContext.assistantSettingsDataStore.edit { prefs ->
            prefs[OPENAI_API_KEY] = updated.openAiApiKey.trim()
            prefs[OPENAI_MODEL] = updated.openAiModel.trim()
            prefs[JOBTREAD_BASE_URL] = updated.jobTreadBaseUrl.trim()
            prefs[JOBTREAD_API_KEY] = updated.jobTreadApiKey.trim()
            prefs[JOBTREAD_ORGANIZATION_ID] = updated.jobTreadOrganizationId.trim()
            prefs[JOBTREAD_ORGANIZATION_NAME] = updated.jobTreadOrganizationName.trim()
        }
    }

    suspend fun getCurrentSettings(): AssistantSettings = settings.first()

    private companion object {
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val JOBTREAD_BASE_URL = stringPreferencesKey("jobtread_base_url")
        val JOBTREAD_API_KEY = stringPreferencesKey("jobtread_api_key")
        val JOBTREAD_ORGANIZATION_ID = stringPreferencesKey("jobtread_organization_id")
        val JOBTREAD_ORGANIZATION_NAME = stringPreferencesKey("jobtread_organization_name")
    }
}
