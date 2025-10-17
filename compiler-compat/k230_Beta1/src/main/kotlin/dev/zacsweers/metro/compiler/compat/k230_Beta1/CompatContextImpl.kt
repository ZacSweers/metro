// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k230_Beta1

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k230_dev_7984.CompatContextImpl as K230Dev7984Impl

public class CompatContextImpl : CompatContext by K230Dev7984Impl() {
  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.3.0-Beta1"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
