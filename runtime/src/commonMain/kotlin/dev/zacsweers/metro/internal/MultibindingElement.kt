// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Qualifier

/**
 * Disambiguates between multiple elements of the same type in a set. Inspired by Guice.
 *
 * @param bindingId The ID of the set.
 * @param elementId The ID of the element.
 */
@Qualifier public annotation class MultibindingElement(val bindingId: String, val elementId: String)
