// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.backend.jvm.ir.fileParentOrNull
import org.jetbrains.kotlin.backend.jvm.ir.getIoFile
import org.jetbrains.kotlin.backend.jvm.ir.getKtFile
import org.jetbrains.kotlin.incremental.components.LocationInfo
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.Position
import org.jetbrains.kotlin.incremental.components.ScopeKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.doNotAnalyze

/**
 * This is a hack for IC using the [IrMetroContext.expectActualTracker] to link the
 * [callingDeclaration] file's compilation to the [calleeDeclaration]. This (ab)uses the
 * expect/actual IC support for cases where the standard [IrMetroContext.lookupTracker] would not
 * suffice.
 *
 * The standard example for this is binding containers, where we may reference a class from a graph
 * but we also want to recompile the graph if _new_ binding declarations are added to the container.
 *
 * See `BindingContainerICTests.addingNewBindingToExistingBindingContainer()` for an example.
 */
context(context: IrMetroContext)
internal fun linkDeclarationsInCompilation(
  callingDeclaration: IrDeclaration,
  calleeDeclaration: IrClass,
) {
  linkDeclarationsInCompilation(callingDeclaration.fileOrNull, calleeDeclaration)
}

context(context: IrMetroContext)
internal fun linkDeclarationsInCompilation(
  callingElement: IrElement,
  calleeDeclaration: IrClass,
) {
  val file = callingElement as? IrFile ?: (calleeDeclaration as IrDeclaration).fileOrNull
  linkDeclarationsInCompilation(file, calleeDeclaration)
}

context(context: IrMetroContext)
internal fun linkDeclarationsInCompilation(
  callingFile: IrFile?,
  calleeDeclaration: IrClass,
) {
  val expectedFile = calleeDeclaration.fileOrNull?.getIoFile() ?: return
  val actualFile = callingFile?.getIoFile() ?: return
  if (expectedFile == actualFile) return
  context.expectActualTracker.report(expectedFile = expectedFile, actualFile = actualFile)
}

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
    val callee =
      if (calleeFunction is IrOverridableDeclaration<*> && calleeFunction.isFakeOverride) {
        calleeFunction.resolveFakeOverrideMaybeAbstract() ?: calleeFunction
      } else {
        calleeFunction
      }
    val declaration: IrDeclarationWithName =
      (callee as? IrSimpleFunction)?.correspondingPropertySymbol?.owner ?: callee
    trackLookup(
      container = callee.parent.kotlinFqName,
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
