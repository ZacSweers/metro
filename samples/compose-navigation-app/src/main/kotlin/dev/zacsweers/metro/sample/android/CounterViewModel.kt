// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package dev.zacsweers.metro.sample.android

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.sample.android.viewmodel.ViewModelKey
import dev.zacsweers.metro.sample.android.viewmodel.ViewModelScope
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A trivial Counter ViewModel. */
@ContributesIntoMap(ViewModelScope::class)
@ViewModelKey(CounterViewModel::class)
@Inject
class CounterViewModel(savedStateHandle: SavedStateHandle, viewModelCounter: AtomicInt) :
  ViewModel() {
  val instance = viewModelCounter.fetchAndIncrement()

  private val route = savedStateHandle.toRoute<Counter>()

  val name: String
    get() = route.name

  private val _count = MutableStateFlow(0)
  val count: StateFlow<Int> = _count

  fun increment() {
    _count.value += 1
  }

  fun decrement() {
    _count.value -= 1
  }
}
