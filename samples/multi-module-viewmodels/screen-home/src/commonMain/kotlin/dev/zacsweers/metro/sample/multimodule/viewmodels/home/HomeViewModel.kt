// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.viewmodels.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.Clock
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.ViewModelKey
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.ViewModelScope
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.formatNow
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Inject
@ViewModelKey(HomeViewModel::class)
@ContributesIntoMap(ViewModelScope::class)
class HomeViewModel(private val clock: Clock) : ViewModel() {
  private val mutableMessage = MutableStateFlow("")
  val message: StateFlow<String> = mutableMessage.asStateFlow()

  init {
    viewModelScope.launch {
      while (true) {
        mutableMessage.update { clock.formatNow() }
        delay(100.milliseconds)
      }
    }
  }
}
