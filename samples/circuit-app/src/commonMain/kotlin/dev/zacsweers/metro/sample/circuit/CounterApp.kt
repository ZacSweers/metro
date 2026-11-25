// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.circuit

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.CircuitContent
import dev.zacsweers.metro.Inject

@Inject
@Composable
fun CounterApp(circuit: Circuit) {
  MaterialTheme { CircuitCompositionLocals(circuit) { CircuitContent(screen = CounterScreen) } }
}
