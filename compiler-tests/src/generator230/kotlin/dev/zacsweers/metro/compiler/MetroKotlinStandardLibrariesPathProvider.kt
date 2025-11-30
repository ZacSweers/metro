// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

// KotlinStandardLibrariesPathProvider was changed to an interface in 2.3.20
abstract class MetroKotlinStandardLibrariesPathProvider : KotlinStandardLibrariesPathProvider()
