// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A ViewModel that demonstrates [ViewModelAssistedFactory] usage with [CreationExtras].
 *
 * Unlike [ManualViewModelAssistedFactory], this factory receives [CreationExtras] which can be used
 * to access Android-specific ViewModel creation context (such as SavedStateHandle).
 */
@AssistedInject
class SettingsViewModel(@Assisted val userId: String) : ViewModel() {
  private val _darkMode = MutableStateFlow(false)
  val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

  private val _notificationsEnabled = MutableStateFlow(true)
  val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

  fun toggleDarkMode() {
    _darkMode.value = !_darkMode.value
  }

  fun toggleNotifications() {
    _notificationsEnabled.value = !_notificationsEnabled.value
  }

  /**
   * Factory that implements [ViewModelAssistedFactory] and is keyed by the ViewModel class using
   * [ViewModelAssistedFactoryKey].
   *
   * This factory receives [CreationExtras] which can be used to extract parameters. In this
   * example, we extract a user ID from the extras.
   */
  @AssistedFactory
  @ViewModelAssistedFactoryKey(SettingsViewModel::class)
  @ContributesIntoMap(AppScope::class)
  fun interface Factory : ViewModelAssistedFactory {
    override fun create(extras: CreationExtras): SettingsViewModel {
      // In a real app, you might extract SavedStateHandle or other extras here
      // For this example, we use a custom key to pass the user ID
      val userId = extras[UserIdKey] ?: "default-user"
      return create(userId)
    }

    fun create(@Assisted userId: String): SettingsViewModel
  }

  companion object {
    /** Custom [CreationExtras.Key] for passing user ID to the ViewModel factory. */
    val UserIdKey = object : CreationExtras.Key<String> {}
  }
}
