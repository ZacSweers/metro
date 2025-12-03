// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.zacsweers.metro.sample.composeviewmodels.details.DetailsScreen
import dev.zacsweers.metro.sample.composeviewmodels.home.HomeScreen
import dev.zacsweers.metro.sample.composeviewmodels.settings.SettingsScreen
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory

@Composable
fun ComposeApp(metroVmf: MetroViewModelFactory, modifier: Modifier = Modifier) {
  CompositionLocalProvider(LocalMetroViewModelFactory provides metroVmf) {
    val navController = rememberNavController()

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
          HomeScreen(
            onNavToDetails = { data -> navController.navigate(DetailsRoute(data)) },
            onNavToSettings = { userId -> navController.navigate(SettingsRoute(userId)) },
          )
        }
        composable<DetailsRoute> { backStackEntry ->
          val route = backStackEntry.toRoute<DetailsRoute>()
          DetailsScreen(data = route.data, onNavBack = { navController.popBackStack() })
        }
        composable<SettingsRoute> { backStackEntry ->
          val route = backStackEntry.toRoute<SettingsRoute>()
          SettingsScreen(userId = route.userId, onNavBack = { navController.popBackStack() })
        }
      }
    }
  }
}
