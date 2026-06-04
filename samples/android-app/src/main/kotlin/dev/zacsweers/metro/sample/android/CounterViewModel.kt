// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey

/** A trivial Counter ViewModel. */
@ContributesIntoMap(AppScope::class)
@ViewModelKey
@Inject
class CounterViewModel : ViewModel() {
  val count: LiveData<Int>
    field: MutableLiveData<Int> = MutableLiveData(0)

  fun increment() {
    count.value = (count.value ?: 0) + 1
  }

  fun decrement() {
    count.value = (count.value ?: 0) - 1
  }
}
