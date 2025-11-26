// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.sample.composeviewmodels.core.LocalViewModelGraphProvider
import dev.zacsweers.metro.sample.composeviewmodels.core.ViewModelGraphProvider
import dev.zacsweers.metrox.android.ActivityKey

@ContributesIntoMap(AppScope::class, binding<Activity>())
@ActivityKey(MainActivity::class)
@Inject
class MainActivity(private val viewModelGraphProvider: ViewModelGraphProvider) :
  ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      CompositionLocalProvider(LocalViewModelGraphProvider provides viewModelGraphProvider) {
        ComposeApp(modifier = Modifier.safeContentPadding())
      }
    }
  }
}
