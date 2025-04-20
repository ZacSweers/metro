// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android.viewmodel

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
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
fun metroViewModelProviderFactory(): ViewModelProvider.Factory {
  return (LocalActivity.current as HasDefaultViewModelProviderFactory)
    .defaultViewModelProviderFactory
}
