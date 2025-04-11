// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.circuit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.slack.circuit.codegen.annotations.CircuitInject
import dev.zacsweers.metro.AppScope

@CircuitInject(CounterScreen::class, AppScope::class)
@Composable
fun Counter(state: CounterState, modifier: Modifier = Modifier) {
  val color = if (state.count >= 0) Color.Unspecified else MaterialTheme.colorScheme.error
  Box(modifier.fillMaxSize()) {
    Column(Modifier.align(Alignment.Center)) {
      Text(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        text = "Count: ${state.count}",
        style = MaterialTheme.typography.labelLarge,
        color = color,
      )
      Spacer(modifier = Modifier.height(16.dp))
      Row {
        Button(
          modifier = Modifier.padding(2.dp),
          onClick = { state.eventSink(CounterEvent.Decrement) },
        ) {
          Icon(rememberVectorPainter(Remove), "Decrement")
        }
        Button(
          onClick = { state.eventSink(CounterEvent.Reset) },
          modifier = Modifier.padding(2.dp),
        ) {
          Text(modifier = Modifier.padding(4.dp), text = "Reset")
        }
        Button(
          modifier = Modifier.padding(2.dp),
          onClick = { state.eventSink(CounterEvent.Increment) },
        ) {
          Icon(rememberVectorPainter(Icons.Filled.Add), "Increment")
        }
      }
    }
  }
}
