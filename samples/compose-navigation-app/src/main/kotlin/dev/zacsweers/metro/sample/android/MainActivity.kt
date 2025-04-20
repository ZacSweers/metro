// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.sample.android.components.ActivityKey
import dev.zacsweers.metro.sample.android.viewmodel.metroViewModel
import kotlinx.serialization.Serializable

@ContributesIntoMap(AppScope::class, binding<Activity>())
@ActivityKey(MainActivity::class)
@Inject
class MainActivity(private val viewModelFactory: ViewModelProvider.Factory) : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      val navController = rememberNavController()
      val onNavigate: (Any) -> Unit = { navController.navigate(it) }

      val appViewModelActivity = metroViewModel<AppViewModel>()

      NavHost(navController, startDestination = Menu) {
        composable<Menu> {
          val appViewModelNavBackStack = metroViewModel<AppViewModel>()

          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Text("Activity View Model Instance: " + appViewModelActivity.instance)
            Text("NavBackStackEntry View Model Instance: " + appViewModelNavBackStack.instance)
            Button(onClick = { onNavigate(Counter("One")) }) { Text("Counter One") }
            Button(onClick = { onNavigate(Counter("Two")) }) { Text("Counter Two") }
          }
        }
        composable<Counter> { CounterScreen(onNavigate) }
      }
    }
  }

  // Use ComponentActivity/HasDefaultViewModelProviderFactory to provide an injected
  // ViewModel Factory
  override val defaultViewModelProviderFactory: ViewModelProvider.Factory
    get() = viewModelFactory
}

@Serializable data object Menu

@Serializable data class Counter(val name: String)
