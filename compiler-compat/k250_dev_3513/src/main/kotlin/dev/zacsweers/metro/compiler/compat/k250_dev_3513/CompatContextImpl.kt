// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k250_dev_3513

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k250_dev_498.CompatContextImpl as DelegateType

public class CompatContextImpl : CompatContext by DelegateType() {
  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.5.0-dev-3513"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
