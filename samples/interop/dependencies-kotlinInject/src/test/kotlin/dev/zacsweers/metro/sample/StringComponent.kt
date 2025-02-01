// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Component abstract class StringComponent(@get:Provides val message: String)
