// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k250_dev_9169

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k250_dev_7307.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.fir.copy
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus

/** Adapts FIR declaration status copying to Kotlin 2.5.0-dev-9169. */
public class CompatContextImpl : CompatContext by DelegateType() {
  override fun FirDeclarationStatus.copyWithOverrideCompat(
    isOverride: Boolean
  ): FirDeclarationStatus = copy(isOverride = isOverride)

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.5.0-dev-9169"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
