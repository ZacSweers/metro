// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.viewmodels.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.zacsweers.metro.sample.multimodule.viewmodels.details.DetailsScreen
import dev.zacsweers.metro.sample.multimodule.viewmodels.home.HomeScreen
import kotlinx.serialization.Serializable

@Composable
internal fun ComposeApp(modifier: Modifier = Modifier) =
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    val navController = rememberNavController()

    NavHost(
      modifier = Modifier.fillMaxSize(),
      navController = navController,
      startDestination = HomeNavRoute,
    ) {
      composable<HomeNavRoute> {
        HomeScreen(onNavToDetails = { data -> navController.navigate(DetailsNavRoute(data)) })
      }

      composable<DetailsNavRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<DetailsNavRoute>()
        DetailsScreen(data = route.data, onNavBack = { navController.popBackStack() })
      }
    }
  }

@Serializable data object HomeNavRoute

@Serializable data class DetailsNavRoute(val data: String)
