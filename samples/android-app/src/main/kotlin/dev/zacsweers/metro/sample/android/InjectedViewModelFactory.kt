// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import kotlin.reflect.KClass

/**
 * A [ViewModelProvider.Factory] that uses an injected map of [KClass] to a provider of [ViewModel]
 * to create ViewModels.
 */
@ContributesBinding(AppScope::class)
@ContributesBinding(AppScope::class, binding<ViewModelProvider.Factory>())
@Inject
class InjectedViewModelFactory(
  override val viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>,
  override val assistedFactoryProviders: Map<KClass<out ViewModel>, () -> ViewModelAssistedFactory>,
  override val manualAssistedFactoryProviders:
    Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory>,
) : MetroViewModelFactory()
