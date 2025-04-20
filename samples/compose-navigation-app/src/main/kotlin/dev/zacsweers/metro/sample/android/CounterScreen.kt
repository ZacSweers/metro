// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import dev.zacsweers.metro.sample.android.viewmodel.metroViewModel

@Composable
fun CounterScreen(onNavigate: (Any) -> Unit) {
  val viewModel = metroViewModel<CounterViewModel>()

  val value = viewModel.count

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Value: ")
    Button(onClick = { onNavigate(Counter("One")) }) { Text("Counter One") }
    Button(onClick = { onNavigate(Counter("Two")) }) { Text("Counter Two") }
  }
}
