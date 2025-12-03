// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.composeviewmodels.app

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute

@Serializable data class DetailsRoute(val data: String)

@Serializable data class SettingsRoute(val userId: String)
