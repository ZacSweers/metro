// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.metroAnnotations
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.getAnnotationStringValue
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.CallableId

/** Representation of the `@CallableMetadata` annotation contents. */
@Poko
internal class IrCallableMetadata(
  val callableId: CallableId,
  val mirrorCallableId: CallableId,
  val annotations: MetroAnnotations<IrAnnotation>,
  val isPropertyAccessor: Boolean,
  @Poko.Skip val function: IrSimpleFunction,
  @Poko.Skip val mirrorFunction: IrSimpleFunction,
)

context(context: IrMetroContext)
internal fun IrSimpleFunction.irCallableMetadata(
  sourceAnnotations: MetroAnnotations<IrAnnotation>?
): IrCallableMetadata {
  return propertyIfAccessor.irCallableMetadata(this, sourceAnnotations)
}

context(context: IrMetroContext)
internal fun IrAnnotationContainer.irCallableMetadata(
  mirrorFunction: IrSimpleFunction,
  sourceAnnotations: MetroAnnotations<IrAnnotation>?,
): IrCallableMetadata {
  val callableMetadataAnno =
    getAnnotation(Symbols.FqNames.CallableMetadataClass)
      ?: error(
        "No @CallableMetadata found on ${expectAsOrNull<IrDeclarationParent>()?.kotlinFqName}. This is a bug in the Metro compiler."
      )
  return callableMetadataAnno.toIrCallableMetadata(mirrorFunction, sourceAnnotations)
}

// TODO for in-compilation, no need to round-trip this
context(context: IrMetroContext)
internal fun IrConstructorCall.toIrCallableMetadata(
  mirrorFunction: IrSimpleFunction,
  sourceAnnotations: MetroAnnotations<IrAnnotation>?,
): IrCallableMetadata {
  val clazz = mirrorFunction.parentAsClass
  val parentClass = clazz.parentAsClass
  val callableName = getAnnotationStringValue("callableName")
  val callableId = CallableId(clazz.classIdOrFail.parentClassId!!, callableName.asName())
  val isPropertyAccessor =
    getConstBooleanArgumentOrNull(Symbols.StringNames.IS_PROPERTY_ACCESSOR.asName()) ?: false
  // Fake a reference to the "real" function by making a copy of this mirror that reflects the
  // real one
  val function =
    mirrorFunction.deepCopyWithSymbols().apply {
      name = callableId.callableName
      // Point at the original class
      parent = parentClass
      setDispatchReceiver(parentClass.thisReceiverOrFail.copyTo(this))
      // Read back the original offsets in the original source
      startOffset = constArgumentOfTypeAt<Int>(2)!!
      endOffset = constArgumentOfTypeAt<Int>(3)!!
    }

  val annotations = sourceAnnotations ?: function.metroAnnotations(context.symbols.classIds)
  return IrCallableMetadata(
    callableId,
    mirrorFunction.callableId,
    annotations,
    isPropertyAccessor,
    function,
    mirrorFunction,
  )
}
