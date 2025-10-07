// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k230_Beta1

import dev.zacsweers.metro.compiler.compat.CompatContext

public class CompatContextImpl : CompatContext {
  // TODO Implement

  public class Factory : CompatContext.Factory {
    override val kotlinVersion: String = "2.3.0-Beta1"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
