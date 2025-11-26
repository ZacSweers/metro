// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.sample.composeviewmodels.core.metroViewModel

@Composable
fun HomeScreen(
  onNavToDetails: (data: String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: HomeViewModel = metroViewModel(),
) =
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    val count by viewModel.count.collectAsState()

    Text(modifier = Modifier.padding(20.dp), text = "Home Screen")
    Text(modifier = Modifier.padding(20.dp), text = "Count: $count")

    Row(modifier = Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      Button(onClick = { viewModel.decrement() }) { Text(text = "-") }
      Button(onClick = { viewModel.increment() }) { Text(text = "+") }
    }

    Row(modifier = Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      Button(onClick = { onNavToDetails("A") }) { Text(text = "Details A") }
      Button(onClick = { onNavToDetails("B") }) { Text(text = "Details B") }
    }
  }
