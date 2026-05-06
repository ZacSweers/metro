// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataKey
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataRegistry
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

/**
 * FIR data key marking a provides function as a wrapper that casts a synthetic scoped instance.
 * Read in IR via `FirMetadataSource` to determine the function body to generate.
 */
internal object IsContributionProviderWrapperKey : FirDeclarationDataKey()

internal var FirFunction.isContributionProviderWrapper: Boolean? by
  FirDeclarationDataRegistry.data(IsContributionProviderWrapperKey)

internal val IrSimpleFunction.isContributionProviderWrapper: Boolean
  get() = (metadata as? FirMetadataSource.Function)?.fir?.isContributionProviderWrapper == true
