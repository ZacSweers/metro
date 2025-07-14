// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.backend.jvm.ir.fileParentOrNull
import org.jetbrains.kotlin.backend.jvm.ir.getKtFile
import org.jetbrains.kotlin.incremental.components.LocationInfo
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.Position
import org.jetbrains.kotlin.incremental.components.ScopeKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.doNotAnalyze

/**
 * Tracks a call from one [callingDeclaration] to a [calleeClass] to inform incremental compilation.
 */
internal fun IrMetroContext.trackClassLookup(
  callingDeclaration: IrDeclaration,
  calleeClass: IrClass,
) {
  callingDeclaration.withAnalyzableKtFile { filePath ->
    trackLookup(
      container = calleeClass.parent.kotlinFqName,
      declarationName = calleeClass.name.asString(),
      scopeKind = ScopeKind.PACKAGE,
      location =
        object : LocationInfo {
          override val filePath = filePath
          override val position: Position = Position.NO_POSITION
        },
    )
  }
}

/**
 * Tracks a call from one [callingDeclaration] to a [calleeFunction] to inform incremental
 * compilation.
 *
 * If the [calleeFunction] is a property getter, the corresponding property will be recorded
 * instead.
 */
internal fun IrMetroContext.trackFunctionCall(
  callingDeclaration: IrDeclaration,
  calleeFunction: IrFunction,
) {
  callingDeclaration.withAnalyzableKtFile { filePath ->
    val declaration =
      (calleeFunction as? IrSimpleFunction)?.correspondingPropertySymbol?.owner ?: calleeFunction
    trackLookup(
      container = calleeFunction.parent.kotlinFqName,
      declarationName = declaration.name.asString(),
      scopeKind = ScopeKind.CLASSIFIER,
      location =
        object : LocationInfo {
          override val filePath = filePath
          override val position: Position = Position.NO_POSITION
        },
    )
  }
}

internal fun IrMetroContext.trackMemberDeclarationCall(
  callingDeclaration: IrDeclaration,
  containerFqName: FqName,
  declarationName: String,
) {
  callingDeclaration.withAnalyzableKtFile { filePath ->
    trackLookup(
      container = containerFqName,
      declarationName = declarationName,
      scopeKind = ScopeKind.CLASSIFIER,
      location =
        object : LocationInfo {
          override val filePath = filePath
          override val position: Position = Position.NO_POSITION
        },
    )
  }
}

internal fun IrMetroContext.trackLookup(
  container: FqName,
  declarationName: String,
  scopeKind: ScopeKind,
  location: LocationInfo,
) {
  withLookupTracker {
    record(
      filePath = location.filePath,
      position = if (requiresPosition) location.position else Position.NO_POSITION,
      scopeFqName = container.asString(),
      scopeKind = scopeKind,
      name = declarationName,
    )
  }
}

internal inline fun IrMetroContext.withLookupTracker(body: LookupTracker.() -> Unit) {
  lookupTracker?.let { tracker -> synchronized(tracker) { tracker.body() } }
}

private fun IrDeclaration.withAnalyzableKtFile(body: (filePath: String) -> Unit) {
  val callingDeclaration = this
  val ktFile = callingDeclaration.fileParentOrNull?.getKtFile()
  if ((ktFile != null && ktFile.doNotAnalyze == null) || ktFile == null) {
    // Not every declaration has a file parent, for example IR-generated accessors
    val filePath =
      ktFile?.virtualFile?.path ?: callingDeclaration.fileParentOrNull?.fileEntry?.name ?: return
    body(filePath)
  }
}
