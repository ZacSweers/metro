// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k230_dev_9992

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k230_Beta1.CompatContextImpl as K230Beta1Impl

// Ships in 2025.3-EAP
public class CompatContextImpl : CompatContext by K230Beta1Impl() {

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.3.0-dev-9992"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
