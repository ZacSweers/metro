// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.core

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.reflect.KClass
import kotlin.reflect.cast

/** Retrieves a Metro-injected ViewModel using the [LocalViewModelGraphProvider]. */
@Composable
inline fun <reified VM : ViewModel> metroViewModel(
  owner: ViewModelStoreOwner = requireViewModelStoreOwner(),
  key: String? = null,
): VM =
  viewModel(viewModelStoreOwner = owner, key = key, factory = LocalViewModelGraphProvider.current)

/**
 * Retrieves a Metro-injected assisted ViewModel using the [LocalViewModelGraphProvider].
 *
 * Note that there's no compile-time validation that [VM] and [VMAF] types match up to each other
 * (yet?).
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
