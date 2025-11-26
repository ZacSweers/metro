// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.zacsweers.metro.sample.composeviewmodels.details.DetailsScreen
import dev.zacsweers.metro.sample.composeviewmodels.home.HomeScreen

@Composable
fun ComposeApp(modifier: Modifier = Modifier) {
  val navController = rememberNavController()

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    NavHost(navController = navController, startDestination = HomeRoute) {
      composable<HomeRoute> {
        HomeScreen(onNavToDetails = { data -> navController.navigate(DetailsRoute(data)) })
      }
      composable<DetailsRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<DetailsRoute>()
        DetailsScreen(data = route.data, onNavBack = { navController.popBackStack() })
      }
    }
  }
}
