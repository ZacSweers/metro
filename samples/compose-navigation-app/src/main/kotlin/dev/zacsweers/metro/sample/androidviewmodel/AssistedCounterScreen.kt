// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.androidviewmodel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.zacsweers.metro.sample.androidviewmodel.viewmodel.metroViewModel

@Composable
fun AssistedCounterScreen(onNavigate: (Any) -> Unit) {
  val viewModel = metroViewModel<AssistedCounterViewModel> { assistedCounterFactory.create(10) }

  val value by viewModel.count.collectAsStateWithLifecycle()

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Owner: ${LocalViewModelStoreOwner.current?.javaClass?.simpleName}")
    Text("Initial Value: ${viewModel.initialValue}")
    Text("View Model Instance: ${viewModel.instance}")
    Text("Value: $value")
    Button(onClick = { viewModel.increment() }) { Text("Increment") }
    Button(onClick = { viewModel.decrement() }) { Text("Decrement") }
    Button(onClick = { onNavigate(Counter("One")) }) { Text("Counter One") }
    Button(onClick = { onNavigate(Counter("Two")) }) { Text("Counter Two") }
  }
}
