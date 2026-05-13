// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k2420_dev_835

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k240_beta2.CompatContextImpl as DelegateType

public class CompatContextImpl : CompatContext by DelegateType() {
  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.4.20-dev-835"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
