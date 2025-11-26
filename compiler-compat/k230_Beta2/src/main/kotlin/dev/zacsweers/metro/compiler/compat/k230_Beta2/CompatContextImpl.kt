// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k230_Beta2

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k230_Beta1.CompatContextImpl as DelegateType

public class CompatContextImpl : CompatContext by DelegateType() {
  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.3.0-Beta2"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
