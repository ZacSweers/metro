// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.circuit

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.CircuitContent
import dev.zacsweers.metro.Inject

// TODO move back to function injection once non-JVM platforms work with top-level FIR injections
@Inject
class CounterAppClass(private val circuit: Circuit) {
  @Composable
  operator fun invoke() {
    CounterApp(circuit)
  }
}

@Composable
fun CounterApp(circuit: Circuit) {
  MaterialTheme { CircuitCompositionLocals(circuit) { CircuitContent(screen = CounterScreen) } }
}
