// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.androidviewmodel.viewmodel

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
inline fun <reified VM : ViewModel> metroViewModel(
  viewModelStoreOwner: ViewModelStoreOwner =
    checkNotNull(LocalViewModelStoreOwner.current) {
      "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
    },
  key: String? = null,
): VM {
  return viewModel(viewModelStoreOwner, key, factory = metroViewModelProviderFactory())
}

@Composable
inline fun <reified VM : ViewModel> metroViewModel(
  viewModelStoreOwner: ViewModelStoreOwner =
    checkNotNull(LocalViewModelStoreOwner.current) {
      "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
    },
  key: String? = null,
  crossinline factory: ViewModelGraph.() -> VM,
): VM {
  val metroViewModelProviderFactory = metroViewModelProviderFactory()
  return viewModel(
    viewModelStoreOwner,
    key,
    factory =
      object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
          val viewModelGraph = metroViewModelProviderFactory.viewModelGraph(extras)
          return modelClass.cast(viewModelGraph.factory())!!
        }
      },
  )
}

@Composable
fun metroViewModelProviderFactory(): MetroViewModelFactory {
  return (LocalActivity.current as HasDefaultViewModelProviderFactory)
    .defaultViewModelProviderFactory as MetroViewModelFactory
}
