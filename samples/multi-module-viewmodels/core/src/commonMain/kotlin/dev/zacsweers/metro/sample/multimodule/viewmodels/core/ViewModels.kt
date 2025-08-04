// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.viewmodels.core

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass
import kotlin.reflect.cast

abstract class ViewModelScope private constructor()

/**
 * Empty interface - only used as a key so we can bind ViewModel @AssistedFactory-annotated
 * implementations into a map. See [assistedMetroViewModel] and
 * [ViewModelGraph.assistedFactoryProviders].
 */
interface ViewModelAssistedFactory

fun interface ViewModelGraphProvider : ViewModelProvider.Factory {
  fun buildViewModelGraph(extras: CreationExtras): ViewModelGraph
}

// Used to inject ViewModel instances into ViewModelGraph
@MapKey
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ViewModelKey(val value: KClass<out ViewModel>)

// Used to inject assisted ViewModel factory instances into ViewModelGraph
@MapKey
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class AssistedFactoryKey(val value: KClass<out ViewModelAssistedFactory>)

// Used to fetch ViewModel instances from ViewModelGraph
@Composable
inline fun <reified VM : ViewModel> metroViewModel(
  owner: ViewModelStoreOwner = requireViewModelStoreOwner(),
  key: String? = null,
): VM =
  viewModel(viewModelStoreOwner = owner, key = key, factory = LocalViewModelGraphProvider.current)

/**
 * Used to fetch assisted ViewModel factory instances from ViewModelGraph. Note that there's no
 * compile-time validation that [VM] and [VMAF] types match up to each other (yet?)
 */
@Composable
inline fun <reified VM : ViewModel, reified VMAF : ViewModelAssistedFactory> assistedMetroViewModel(
  owner: ViewModelStoreOwner = requireViewModelStoreOwner(),
  key: String? = null,
  crossinline buildViewModel: VMAF.() -> VM,
): VM {
  val graphProvider = LocalViewModelGraphProvider.current
  return viewModel(
    viewModelStoreOwner = owner,
    key = key,
    factory =
      object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
          val nullableProvider =
            graphProvider
              .buildViewModelGraph(extras)
              .assistedFactoryProviders[VMAF::class]
              ?.invoke()
              ?.let(VMAF::class::cast)

          val factoryProvider =
            requireNotNull(nullableProvider) {
              "No factory found for class ${VMAF::class.qualifiedName}"
            }

          return modelClass.cast(factoryProvider.buildViewModel())
        }
      },
  )
}

@Composable
fun requireViewModelStoreOwner() =
  checkNotNull(LocalViewModelStoreOwner.current) {
    "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
  }
