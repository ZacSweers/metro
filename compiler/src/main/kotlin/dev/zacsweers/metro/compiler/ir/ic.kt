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
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.doNotAnalyze

/**
 * Tracks a call from one [callingDeclaration] to a [calleeClass] to inform incremental compilation.
 */
context(context: IrMetroContext)
internal fun trackClassLookup(callingDeclaration: IrDeclaration, calleeClass: IrClass) {
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
context(context: IrMetroContext)
internal fun trackFunctionCall(callingDeclaration: IrDeclaration, calleeFunction: IrFunction) {
  callingDeclaration.withAnalyzableKtFile { filePath ->
    val declaration: IrDeclarationWithName =
      (calleeFunction as? IrSimpleFunction)?.correspondingPropertySymbol?.owner ?: calleeFunction
    check(!declaration.isFakeOverride) {
      "Cannot track fake overrides: $declaration"
    }
    checkNotNull(declaration.locationOrNull()) {
      "Cannot track declaration without source: $declaration"
    }
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

context(context: IrMetroContext)
internal fun trackMemberDeclarationCall(
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

context(context: IrMetroContext)
internal fun trackLookup(
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

context(context: IrMetroContext)
internal inline fun withLookupTracker(body: LookupTracker.() -> Unit) {
  context.lookupTracker?.let { tracker -> synchronized(tracker) { tracker.body() } }
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
