// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import dev.zacsweers.metro.sample.composeviewmodels.details.DetailsScreen
import dev.zacsweers.metro.sample.composeviewmodels.home.HomeScreen
import dev.zacsweers.metro.sample.composeviewmodels.settings.SettingsScreen
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory

@Composable
fun ComposeApp(metroVmf: MetroViewModelFactory, modifier: Modifier = Modifier) {
  CompositionLocalProvider(LocalMetroViewModelFactory provides metroVmf) {
    val backStack = remember { mutableStateListOf<Route>(HomeRoute) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = { route ->
          when (route) {
            is HomeRoute ->
              NavEntry(route) {
                HomeScreen(
                  onNavToDetails = { data -> backStack.add(DetailsRoute(data)) },
                  onNavToSettings = { userId -> backStack.add(SettingsRoute(userId)) },
                )
              }
            is DetailsRoute ->
              NavEntry(route) {
                DetailsScreen(data = route.data, onNavBack = { backStack.removeLastOrNull() })
              }
            is SettingsRoute ->
              NavEntry(route) {
                SettingsScreen(userId = route.userId, onNavBack = { backStack.removeLastOrNull() })
              }
          }
        },
      )
    }
  }
}
