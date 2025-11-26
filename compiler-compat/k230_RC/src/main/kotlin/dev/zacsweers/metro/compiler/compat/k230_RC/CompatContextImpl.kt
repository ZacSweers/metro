// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k230_RC

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k230_Beta2.CompatContextImpl as DelegateType

public class CompatContextImpl : CompatContext by DelegateType() {
  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.3.0-RC"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
