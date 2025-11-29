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
@ViewModelKey(CounterViewModel::class)
@Inject
class CounterViewModel : ViewModel() {
  private val _count = MutableLiveData(0)
  val count: LiveData<Int> = _count

  fun increment() {
    _count.value = (_count.value ?: 0) + 1
  }

  fun decrement() {
    _count.value = (_count.value ?: 0) - 1
  }
}
