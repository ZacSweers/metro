// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k2520_dev_4223

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k250_dev_10106.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.resolve.TypeResolutionConfiguration
import org.jetbrains.kotlin.fir.scopes.FirScope

/** Adapts type-resolution configuration construction to Kotlin 2.5.20-dev-4223. */
public class CompatContextImpl : CompatContext by DelegateType() {
  override fun createTypeResolutionConfigurationCompat(
    scopes: Iterable<FirScope>,
    useSiteFile: FirFile?,
  ): TypeResolutionConfiguration =
    TypeResolutionConfiguration(scopes, emptyList(), useSiteFile = useSiteFile)

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.5.20-dev-4223"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
