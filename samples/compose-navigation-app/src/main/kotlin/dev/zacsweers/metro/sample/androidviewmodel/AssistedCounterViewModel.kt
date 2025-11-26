// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package dev.zacsweers.metro.sample.androidviewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A trivial Assisted Counter ViewModel. */
@AssistedInject
class AssistedCounterViewModel(
  @Assisted val initialValue: Int,
  savedStateHandle: SavedStateHandle,
  viewModelCounter: AtomicInt,
) : ViewModel() {
  val instance = viewModelCounter.fetchAndIncrement()

  val name: String
    get() = "Assisted"

  private val _count = MutableStateFlow(initialValue)
  val count: StateFlow<Int> = _count

  fun increment() {
    _count.value += 1
  }

  fun decrement() {
    _count.value -= 1
  }

  @AssistedFactory
  fun interface Factory {
    fun create(initialValue: Int): AssistedCounterViewModel
  }
}
