// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.details

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
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel

@Composable
fun DetailsScreen(
  data: String,
  onNavBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: DetailsViewModel =
    assistedMetroViewModel<DetailsViewModel, DetailsViewModel.Factory> { create(data) },
) =
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    val count by viewModel.count.collectAsState()

    Text(modifier = Modifier.padding(20.dp), text = "Details Screen: ${viewModel.data}")
    Text(modifier = Modifier.padding(20.dp), text = "Count: $count")

    Row(modifier = Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      Button(onClick = { viewModel.decrement() }) { Text(text = "-") }
      Button(onClick = { viewModel.increment() }) { Text(text = "+") }
    }

    Button(modifier = Modifier.padding(20.dp), onClick = onNavBack) { Text("Back") }
  }
