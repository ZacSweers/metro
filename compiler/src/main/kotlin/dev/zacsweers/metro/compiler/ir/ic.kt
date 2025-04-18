package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.backend.jvm.ir.fileParentOrNull
import org.jetbrains.kotlin.backend.jvm.ir.getKtFile
import org.jetbrains.kotlin.incremental.components.LocationInfo
import org.jetbrains.kotlin.incremental.components.Position
import org.jetbrains.kotlin.incremental.components.ScopeKind
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.doNotAnalyze

internal fun IrMetroContext.trackFunctionCall(
  callingDeclaration: IrDeclaration,
  calleeFunction: IrFunction,
) {
  val ktFile = callingDeclaration.fileParentOrNull?.getKtFile()
  if ((ktFile != null && ktFile.doNotAnalyze == null) || ktFile == null) {
    // Not every declaration has a file parent, for example IR-generated accessors
    val filePath =
      ktFile?.virtualFile?.path ?: callingDeclaration.fileParentOrNull?.fileEntry?.name ?: return
    val declaration =
      (calleeFunction as? IrSimpleFunction)?.correspondingPropertySymbol?.owner ?: calleeFunction
    trackLookup(
      container = calleeFunction.parentAsClass.kotlinFqName,
      functionName = declaration.name.asString(),
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
  functionName: String,
  location: LocationInfo,
) {
  lookupTracker?.let { tracker ->
    synchronized(tracker) {
      tracker.record(
        filePath = location.filePath,
        position = if (tracker.requiresPosition) location.position else Position.NO_POSITION,
        scopeFqName = container.asString(),
        scopeKind = ScopeKind.CLASSIFIER,
        name = functionName,
      )
    }
  }
}