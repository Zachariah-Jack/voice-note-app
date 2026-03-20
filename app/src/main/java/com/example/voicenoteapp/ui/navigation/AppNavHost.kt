package com.example.voicenoteapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.voicenoteapp.assistant.CreateTodoParser
import com.example.voicenoteapp.data.repo.VoiceNotesRepository
import com.example.voicenoteapp.jobtread.JobTreadLookupRepository
import com.example.voicenoteapp.settings.CredentialStore
import com.example.voicenoteapp.ui.screens.DriveHomeScreen
import com.example.voicenoteapp.ui.screens.DriveHomeViewModel
import com.example.voicenoteapp.ui.screens.JobTreadAssistantScreen
import com.example.voicenoteapp.ui.screens.JobTreadAssistantViewModel
import com.example.voicenoteapp.ui.screens.JobDetailScreen
import com.example.voicenoteapp.ui.screens.JobDetailViewModel
import com.example.voicenoteapp.ui.screens.JobListScreen
import com.example.voicenoteapp.ui.screens.JobListViewModel
import com.example.voicenoteapp.ui.screens.NoteEditorScreen
import com.example.voicenoteapp.ui.screens.NoteEditorViewModel
import com.example.voicenoteapp.ui.screens.SettingsScreen
import com.example.voicenoteapp.ui.screens.UnsavedDraftsScreen
import com.example.voicenoteapp.ui.screens.UnsavedDraftsViewModel
import com.example.voicenoteapp.ui.screens.VoiceWizardScreen
import com.example.voicenoteapp.ui.screens.VoiceWizardViewModel

@Composable
fun AppNavHost(
    repository: VoiceNotesRepository,
    credentialStore: CredentialStore,
    createTodoParser: CreateTodoParser,
    jobTreadLookupRepository: JobTreadLookupRepository,
    startDestination: String = Route.DriveHome.route,
    externalRoute: String? = null,
    onExternalRouteHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    LaunchedEffect(externalRoute) {
        if (externalRoute != null) {
            navController.navigate(externalRoute) {
                popUpTo(Route.DriveHome.route) { inclusive = false }
                launchSingleTop = true
            }
            onExternalRouteHandled()
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Route.DriveHome.route) {
            val vm = viewModel<DriveHomeViewModel>(factory = simpleFactory {
                DriveHomeViewModel(repository)
            })
            DriveHomeScreen(
                viewModel = vm,
                onStartJobTreadAssistant = { navController.navigate(Route.JobTreadAssistant.route) },
                onOpenLegacyVoiceNotes = { navController.navigate(Route.VoiceWizard.create(null, null)) },
                onOpenUnsavedDrafts = { navController.navigate(Route.UnsavedDrafts.route) },
                onOpenJobs = { navController.navigate(Route.JobList.route) }
            )
        }
        composable(Route.JobTreadAssistant.route) {
            val vm = viewModel<JobTreadAssistantViewModel>(factory = simpleFactory {
                JobTreadAssistantViewModel(
                    credentialStore = credentialStore,
                    createTodoParser = createTodoParser,
                    jobTreadLookupRepository = jobTreadLookupRepository
                )
            })
            JobTreadAssistantScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Route.Settings.route) }
            )
        }
        composable(Route.UnsavedDrafts.route) {
            val vm = viewModel<UnsavedDraftsViewModel>(factory = simpleFactory {
                UnsavedDraftsViewModel(repository)
            })
            UnsavedDraftsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onResumeDraft = { draftId ->
                    navController.navigate(Route.VoiceWizard.create(draftId, null))
                }
            )
        }
        composable(Route.JobList.route) {
            val vm = viewModel<JobListViewModel>(factory = simpleFactory {
                JobListViewModel(repository)
            })
            JobListScreen(
                viewModel = vm,
                onOpenJob = { navController.navigate(Route.JobDetail.create(it)) },
                onQuickAddNote = { navController.navigate(Route.NoteEditor.create(it, null)) },
                onOpenSettings = { navController.navigate(Route.Settings.route) }
            )
        }
        composable(Route.Settings.route) {
            SettingsScreen(
                credentialStore = credentialStore,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Route.JobDetail.route,
            arguments = listOf(navArgument("jobId") { type = NavType.LongType })
        ) { backStackEntry ->
            val jobId = backStackEntry.arguments?.getLong("jobId") ?: return@composable
            val vm = viewModel<JobDetailViewModel>(factory = simpleFactory {
                JobDetailViewModel(repository, jobId)
            })
            JobDetailScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onAddNote = { navController.navigate(Route.NoteEditor.create(jobId, null)) },
                onOpenNote = { noteId -> navController.navigate(Route.NoteEditor.create(jobId, noteId)) }
            )
        }
        composable(
            route = Route.NoteEditor.route,
            arguments = listOf(
                navArgument("jobId") { type = NavType.LongType },
                navArgument("noteId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val jobId = backStackEntry.arguments?.getLong("jobId") ?: return@composable
            val noteIdArg = backStackEntry.arguments?.getLong("noteId") ?: -1L
            val noteId = noteIdArg.takeIf { it > 0L }
            val vm = viewModel<NoteEditorViewModel>(factory = simpleFactory {
                NoteEditorViewModel(repository, jobId, noteId)
            })

            NoteEditorScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
                onEditByVoice = noteId?.let { existingId ->
                    {
                        navController.navigate(Route.VoiceWizard.create(null, existingId))
                    }
                }
            )
        }
        composable(
            route = Route.VoiceWizard.route,
            arguments = listOf(
                navArgument("draftId") { type = NavType.LongType },
                navArgument("noteId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val draftIdArg = backStackEntry.arguments?.getLong("draftId") ?: -1L
            val noteIdArg = backStackEntry.arguments?.getLong("noteId") ?: -1L
            val draftId = draftIdArg.takeIf { it > 0L }
            val noteId = noteIdArg.takeIf { it > 0L }

            val vm = viewModel<VoiceWizardViewModel>(factory = simpleFactory {
                VoiceWizardViewModel(repository, draftId, noteId)
            })
            VoiceWizardScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onExit = {
                    navController.navigate(Route.DriveHome.route) {
                        popUpTo(Route.DriveHome.route) { inclusive = true }
                    }
                }
            )
        }
    }
}

private inline fun <reified T : ViewModel> simpleFactory(crossinline create: () -> T): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <VM : ViewModel> create(modelClass: Class<VM>, extras: CreationExtras): VM {
            @Suppress("UNCHECKED_CAST")
            return create() as VM
        }

        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
            @Suppress("UNCHECKED_CAST")
            return create() as VM
        }
    }
}
