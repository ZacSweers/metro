// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.androidviewmodel

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.sample.androidviewmodel.viewmodel.metroViewModel
import dev.zacsweers.metrox.android.ActivityKey
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

      val appOwner = LocalViewModelStoreOwner.current
      val appViewModelActivity = metroViewModel<AppViewModel>()

      NavHost(
        navController,
        startDestination = Menu,
        modifier = Modifier.fillMaxSize().safeContentPadding(),
      ) {
        composable<Menu> {
          val routeOwner = LocalViewModelStoreOwner.current
          val routeViewModel = metroViewModel<AppViewModel>()

          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Text("App Owner: ${appOwner?.javaClass?.simpleName}")
            Text("App View Model Instance: ${appViewModelActivity.instance}")
            Text("Route Owner: ${routeOwner?.javaClass?.simpleName}")
            Text("Route View Model Instance: ${routeViewModel.instance}")
            Button(onClick = { onNavigate(Counter("One")) }) { Text("Counter One") }
            Button(onClick = { onNavigate(Counter("Two")) }) { Text("Counter Two") }
            Button(onClick = { onNavigate(AssistedCounter) }) { Text("Assisted Counter") }
          }
        }
        composable<Counter> { CounterScreen(onNavigate) }
        composable<AssistedCounter> { AssistedCounterScreen(onNavigate) }
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

@Serializable data object AssistedCounter
