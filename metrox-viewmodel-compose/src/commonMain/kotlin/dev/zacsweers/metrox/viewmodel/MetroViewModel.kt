// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.viewmodel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.reflect.KClass
import kotlin.reflect.cast

/**
 * CompositionLocal for providing the [MetroViewModelFactory] in Compose. This allows
 * [metroViewModel] and [assistedMetroViewModel] overloads to access a factory.
 */
public val LocalMetroViewModelFactory: ProvidableCompositionLocal<MetroViewModelFactory> =
  staticCompositionLocalOf {
    error("No MetroViewModelFactory registered")
  }

/** Retrieves a Metro-injected ViewModel using the [LocalMetroViewModelFactory]. */
@Composable
public inline fun <reified VM : ViewModel> metroViewModel(
  viewModelStoreOwner: ViewModelStoreOwner = requireViewModelStoreOwner(),
  key: String? = null,
): VM =
  viewModel(
    viewModelStoreOwner = viewModelStoreOwner,
    key = key,
    factory = LocalMetroViewModelFactory.current,
  )

/** Retrieves a Metro-injected ViewModel using the [LocalMetroViewModelFactory]. */
@Composable
public inline fun <reified VM : ViewModel> assistedMetroViewModel(
  viewModelStoreOwner: ViewModelStoreOwner = requireViewModelStoreOwner(),
  key: String? = null,
  extras: CreationExtras =
    if (viewModelStoreOwner is HasDefaultViewModelProviderFactory) {
      viewModelStoreOwner.defaultViewModelCreationExtras
    } else {
      CreationExtras.Empty
    },
): VM =
  viewModel(
    viewModelStoreOwner = viewModelStoreOwner,
    key = key,
    factory = LocalMetroViewModelFactory.current,
  )

/**
 * Retrieves a Metro-injected [ManualViewModelAssistedFactory] using the
 * [LocalMetroViewModelFactory].
 */
@Composable
public inline fun <
  reified VM : ViewModel,
  reified FactoryType : ManualViewModelAssistedFactory,
> assistedMetroViewModel(
  viewModelStoreOwner: ViewModelStoreOwner = requireViewModelStoreOwner(),
  key: String? = null,
  extras: CreationExtras =
    if (viewModelStoreOwner is HasDefaultViewModelProviderFactory) {
      viewModelStoreOwner.defaultViewModelCreationExtras
    } else {
      CreationExtras.Empty
    },
  crossinline createViewModel: FactoryType.() -> VM,
): VM {
  val factory = LocalMetroViewModelFactory.current
  return viewModel(
    viewModelStoreOwner = viewModelStoreOwner,
    key = key,
    factory =
      object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
          val factoryClass = FactoryType::class
          val provider = factory.createManuallyAssistedFactory(factoryClass)

          return modelClass.cast(provider().createViewModel())
        }
      },
  )
}

@Composable
@PublishedApi
internal fun requireViewModelStoreOwner(): ViewModelStoreOwner =
  checkNotNull(LocalViewModelStoreOwner.current) {
    "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
  }
