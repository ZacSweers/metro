// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.Provider
import kotlin.reflect.KClass

/**
 * Factory class for creating [ViewModel] instances and their assisted factories.
 *
 * This can be provided on a DI graph and then installed wherever a default
 * [ViewModelProvider.Factory] is expected. It can be helpful to use in tandem with
 * [ViewModelGraph].
 *
 * Below is the recommended installation, where you subclass this class and contribute an injected
 * binding. You can override whichever provider maps you need to support.
 *
 * ```kotlin
 * @DependencyGraph(AppScope::class)
 * interface MyGraph : ViewModelGraph
 *
 * @Inject
 * @ContributesBinding(AppScope::class)
 * @SingleIn(AppScope::class)
 * class MyViewModelFactory(
 *   override val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>>,
 *   override val assistedFactoryProviders: Map<KClass<out ViewModel>, Provider<ViewModelAssistedFactory>>,
 *   override val manualAssistedFactoryProviders: Map<KClass<out ManualViewModelAssistedFactory>, Provider<ManualViewModelAssistedFactory>>,
 * ): MetroViewModelFactory()
 *
 * // Compose installation
 * @Composable
 * class AppEntry(metroVmf: MetroViewModelFactory) {
 *   CompositionLocalProvider(LocalMetroViewModelFactory provides metroVmf) {
 *     App(...)
 *   }
 * }
 *
 * // Activity installation
 * @Inject
 * class ExampleActivity(private val viewModelFactory: MyViewModelFactory) : ComponentActivity() {
 *   override val defaultViewModelProviderFactory: ViewModelProvider.Factory
 *     get() = viewModelFactory
 * }
 *
 * // Fragment installation
 * @Inject
 * class ExampleFragment(private val viewModelFactory: MyViewModelFactory) : Fragment() {
 *   override val defaultViewModelProviderFactory: ViewModelProvider.Factory
 *     get() = viewModelFactory
 * }
 * ```
 *
 * The class manages two maps:
 * - [viewModelProviders]: A map of [KClass] to [Provider] of ViewModels, used to instantiate
 *   standard constructor-injected ViewModels.
 * - [assistedFactoryProviders]: A map of KClass to a Provider of [ViewModelAssistedFactory]
 *   instances, used to create ViewModels that require assisted creation with [CreationExtras]. This
 *   map is always tried first.
 *
 * The keys for both maps are the target ViewModel class.
 *
 * If neither map contains the requested ViewModel class, an `IllegalArgumentException` is thrown.
 */
public abstract class MetroViewModelFactory : ViewModelProvider.Factory {
  protected open val viewModelProviders: Map<KClass<out ViewModel>, Provider<ViewModel>> =
    emptyMap()
  protected open val assistedFactoryProviders:
    Map<KClass<out ViewModel>, Provider<ViewModelAssistedFactory>> =
    emptyMap()
  protected open val manualAssistedFactoryProviders:
    Map<KClass<out ManualViewModelAssistedFactory>, Provider<ManualViewModelAssistedFactory>> =
    emptyMap()

  @Suppress("UNCHECKED_CAST")
  final override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
    assistedFactoryProviders[modelClass]?.let { factory ->
      return factory().create(extras) as T
    }

    viewModelProviders[modelClass]?.let { provider ->
      return provider() as T
    }

    throw IllegalArgumentException("Unknown model class $modelClass")
  }

  public fun <FactoryType : ManualViewModelAssistedFactory> createManuallyAssistedFactory(
    factoryClass: KClass<FactoryType>
  ): Provider<FactoryType> {
    manualAssistedFactoryProviders[factoryClass]?.let { provider ->
      @Suppress("UNCHECKED_CAST")
      return provider as Provider<FactoryType>
    }
    error("No manual viewModel provider found for $factoryClass")
  }
}
