// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.MutableCreationExtras
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel

@Composable
fun SettingsScreen(
  userId: String,
  onNavBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SettingsViewModel =
    assistedMetroViewModel(
      extras =
        remember(userId) {
          MutableCreationExtras().apply { set(SettingsViewModel.UserIdKey, userId) }
        }
    ),
) =
  Column(
    modifier = modifier.fillMaxSize().padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    val darkMode by viewModel.darkMode.collectAsState()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()

    Text(modifier = Modifier.padding(20.dp), text = "Settings for '${viewModel.userId}' (Demo)")

    Row(
      modifier = Modifier.padding(vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text = "Dark Mode")
      Switch(checked = darkMode, onCheckedChange = { viewModel.toggleDarkMode() })
    }

    Row(
      modifier = Modifier.padding(vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text = "Notifications")
      Switch(checked = notificationsEnabled, onCheckedChange = { viewModel.toggleNotifications() })
    }

    Button(modifier = Modifier.padding(20.dp), onClick = onNavBack) { Text("Back") }
  }
