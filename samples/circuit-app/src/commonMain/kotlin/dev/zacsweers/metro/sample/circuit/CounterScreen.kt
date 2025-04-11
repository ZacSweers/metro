// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.circuit

import com.slack.circuit.runtime.CircuitUiEvent
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen

data object CounterScreen : Screen

data class CounterState(val count: Int, val eventSink: (CounterEvent) -> Unit) : CircuitUiState

sealed interface CounterEvent : CircuitUiEvent {
  data object Increment : CounterEvent

  data object Decrement : CounterEvent

  data object Reset : CounterEvent
}

