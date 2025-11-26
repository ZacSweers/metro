// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.details

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.sample.composeviewmodels.core.AssistedFactoryKey
import dev.zacsweers.metro.sample.composeviewmodels.core.ViewModelAssistedFactory
import dev.zacsweers.metro.sample.composeviewmodels.core.ViewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A ViewModel that demonstrates assisted injection with a [data] parameter. */
@AssistedInject
class DetailsViewModel(@Assisted val data: String) : ViewModel() {
  private val _count = MutableStateFlow(0)
  val count: StateFlow<Int> = _count.asStateFlow()

  fun increment() {
    _count.value++
  }

  fun decrement() {
    _count.value--
  }

  @AssistedFactory
  @AssistedFactoryKey(Factory::class)
  @ContributesIntoMap(ViewModelScope::class)
  fun interface Factory : ViewModelAssistedFactory {
    fun create(@Assisted data: String): DetailsViewModel
  }
}
