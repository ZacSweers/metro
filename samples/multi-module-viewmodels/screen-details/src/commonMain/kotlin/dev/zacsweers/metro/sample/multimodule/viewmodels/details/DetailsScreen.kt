// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.viewmodels.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.assistedMetroViewModel

@Composable
fun DetailsScreen(
  data: String,
  onNavBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: DetailsViewModel =
    assistedMetroViewModel<DetailsViewModel, DetailsViewModel.Factory> { create(data) },
) =
  Column(
    modifier = modifier.background(color = Color.Yellow),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    val message by viewModel.message.collectAsState()

    Text(modifier = Modifier.padding(20.dp), text = "Details screen: $message")

    Button(modifier = Modifier.padding(20.dp), onClick = onNavBack) { Text("Back") }
  }
