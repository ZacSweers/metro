// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.metroCheck

/**
 * Helper interface to lock down core transformers in [CoreTransformers]. Eventually, we'd rather
 * those transformers just report their aggregated data in some way and hide the ability for graph
 * transformation to actually try to run their transformation code.
 */
internal sealed interface Lockable {
  fun lock()

  fun checkNotLocked()

  companion object {
    operator fun invoke(): Lockable = Impl()
  }

  private class Impl : Lockable {
    private var locked = false

    override fun lock() {
      locked = true
    }

    override fun checkNotLocked() {
      metroCheck(!locked) { "Transforming after locked!" }
    }
  }
}
