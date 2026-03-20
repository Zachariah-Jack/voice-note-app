package com.example.voicenoteapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.room.Room
import com.example.voicenoteapp.assistant.AutomaticCreateTodoParser
import com.example.voicenoteapp.assistant.FakeCreateTodoParser
import com.example.voicenoteapp.assistant.OpenAiCreateTodoParser
import com.example.voicenoteapp.data.db.AppDatabase
import com.example.voicenoteapp.data.repo.VoiceNotesRepository
import com.example.voicenoteapp.notifications.DraftReminderScheduler
import com.example.voicenoteapp.settings.CredentialStore
import com.example.voicenoteapp.ui.navigation.AppNavHost
import com.example.voicenoteapp.ui.navigation.Route
import com.example.voicenoteapp.ui.theme.VoiceNoteAppTheme

class MainActivity : ComponentActivity() {
    private var startRoute: String = Route.DriveHome.route
    private var externalRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DraftReminderScheduler.schedule(applicationContext)
        startRoute = routeFromIntent(intent)
        if (startRoute == Route.UnsavedDrafts.route) {
            intent.action = null
        }

        setContent {
            val db = remember {
                Room.databaseBuilder(
                    applicationContext,
                    AppDatabase::class.java,
                    AppDatabase.DB_NAME
                )
                    .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
            }
            val repository = remember { VoiceNotesRepository(db) }
            val credentialStore = remember { CredentialStore(applicationContext) }
            val createTodoParser = remember {
                AutomaticCreateTodoParser(
                    openAiParser = OpenAiCreateTodoParser(),
                    fallbackParser = FakeCreateTodoParser()
                )
            }

            VoiceNoteAppTheme {
                AppNavHost(
                    repository = repository,
                    credentialStore = credentialStore,
                    createTodoParser = createTodoParser,
                    startDestination = startRoute,
                    externalRoute = externalRoute,
                    onExternalRouteHandled = { externalRoute = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val route = routeFromIntent(intent)
        if (route == Route.UnsavedDrafts.route) {
            externalRoute = route
            intent.action = null
        }
    }

    private fun routeFromIntent(intent: android.content.Intent?): String {
        return if (intent?.action == AppDeepLinks.ACTION_OPEN_UNSAVED_DRAFTS) {
            Route.UnsavedDrafts.route
        } else {
            Route.DriveHome.route
        }
    }
}
