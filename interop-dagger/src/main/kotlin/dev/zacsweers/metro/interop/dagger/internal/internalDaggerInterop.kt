// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.interop.dagger.internal

import dagger.internal.Factory as DaggerFactory
import dev.zacsweers.metro.internal.Factory as MetroFactory

public fun <T : Any> DaggerFactory<T>.asMetroFactory(): MetroFactory<T> =
  object : MetroFactory<T> {
    override fun invoke(): T {
      return this@asMetroFactory.get()
    }
  }
