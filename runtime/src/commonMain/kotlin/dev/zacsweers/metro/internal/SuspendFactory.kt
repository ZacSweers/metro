// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.SuspendProvider

public fun interface SuspendFactory<T> : SuspendProvider<T>
