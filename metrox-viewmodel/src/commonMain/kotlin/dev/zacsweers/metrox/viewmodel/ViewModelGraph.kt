// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.viewmodel

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provider
import kotlin.reflect.KClass

/**
 * Contains common multibindings for standard ViewModels and assisted ViewModels.
 *
 * Extend this in a dependency graph that needs to only needs to expose viewmodel provider map
 * multibindings. If you want [MetroViewModelFactory] access, extend [ViewModelGraph] instead.
 *
 * ```kotlin
 * @DependencyGraph
 * interface MyGraph : MetroViewModelMultibindings
 * ```
 */
public interface MetroViewModelMultibindings {
  @Multibinds(allowEmpty = true)
  public val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>>

  @Multibinds(allowEmpty = true)
  public val assistedFactoryProviders:
    Map<KClass<out ViewModel>, Provider<ViewModelAssistedFactory>>

  @Multibinds(allowEmpty = true)
  public val manualAssistedFactoryProviders:
    Map<KClass<out ManualViewModelAssistedFactory>, Provider<ManualViewModelAssistedFactory>>
}

/**
 * Extension of [MetroViewModelMultibindings] that also includes a [MetroViewModelFactory] accessor.
 * Extend this in a dependency graph that needs to expose [MetroViewModelFactory].
 *
 * ```kotlin
 * @DependencyGraph
 * interface MyGraph : ViewModelGraph
 * ```
 */
public interface ViewModelGraph : MetroViewModelMultibindings {
  public val metroViewModelFactory: MetroViewModelFactory
}
