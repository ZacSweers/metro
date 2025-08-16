// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.viewmodels.core

import androidx.compose.runtime.staticCompositionLocalOf

val LocalViewModelGraphProvider =
  staticCompositionLocalOf<ViewModelGraphProvider> { error("No ViewModelGraphProvider registered") }
