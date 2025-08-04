// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalTime::class)

package dev.zacsweers.metro.sample.multimodule.compose.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.sample.multimodule.compose.core.AssistedFactoryKey
import dev.zacsweers.metro.sample.multimodule.compose.core.ViewModelAssistedFactory
import dev.zacsweers.metro.sample.multimodule.compose.core.ViewModelKey
import dev.zacsweers.metro.sample.multimodule.compose.core.ViewModelScope
import dev.zacsweers.metro.sample.multimodule.compose.core.formatNow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

@Inject
@ViewModelKey(DetailsViewModel::class)
class DetailsViewModel(@Assisted private val data: String, private val clock: Clock) : ViewModel() {
  private val mutableMessage = MutableStateFlow("")
  val message: StateFlow<String> = mutableMessage.asStateFlow()

  init {
    viewModelScope.launch {
      while (true) {
        mutableMessage.update { "${clock.formatNow()} - assisted data = $data" }
        delay(100.milliseconds)
      }
    }
  }

  @AssistedFactory
  @AssistedFactoryKey(Factory::class)
  @ContributesIntoMap(ViewModelScope::class, binding<ViewModelAssistedFactory>())
  fun interface Factory : ViewModelAssistedFactory {
    fun create(@Assisted data: String): DetailsViewModel
  }
}
