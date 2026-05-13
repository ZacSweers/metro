// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.api.fir

import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataKey
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataRegistry
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.name.ClassId

/**
 * Designates an [originClassId] for a given FIR class. This is useful for situations where you
 * cannot generate an `@Origin` annotation for some reason.
 */
public data class MetroOriginData(val originClassId: ClassId) {
  internal object Attribute : FirDeclarationDataKey()
}

public var FirClass.metroOriginData: MetroOriginData? by
  FirDeclarationDataRegistry.data(MetroOriginData.Attribute)

public val IrClass.metroOriginData: MetroOriginData?
  get() = (metadata as? FirMetadataSource.Class)?.fir?.metroOriginData
