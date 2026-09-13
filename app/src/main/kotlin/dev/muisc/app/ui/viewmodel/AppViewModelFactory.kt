package dev.muisc.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.muisc.app.di.AppGraph

/**
 * Manual-DI factory: every ViewModel pulls what it needs from [AppGraph]. Screens call
 * `viewModel<LibraryViewModel>(factory = AppViewModelFactory)`.
 */
object AppViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(LibraryViewModel::class.java) -> LibraryViewModel(AppGraph.libraryRepository) as T
        modelClass.isAssignableFrom(PlayerViewModel::class.java) -> PlayerViewModel() as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(AppGraph.settings) as T
        modelClass.isAssignableFrom(LabViewModel::class.java) -> LabViewModel(AppGraph.libraryRepository) as T
        else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
}
