// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.home

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class HomeViewModel : ViewModel() {
  val count: StateFlow<Int>
    field: MutableStateFlow<Int> = MutableStateFlow(0)

  fun increment() {
    count.value++
  }

  fun decrement() {
    count.value--
  }
}
