// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
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
      ?: reportCompilerBug(
        "No @CallableMetadata found on ${expectAsOrNull<IrDeclarationParent>()?.kotlinFqName}"
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
  val propertyName = getAnnotationStringValue("propertyName").takeUnless { it.isBlank() }
  val callableId = CallableId(clazz.classIdOrFail.parentClassId!!, callableName.asName())
  // Fake a reference to the "real" function by making a copy of this mirror that reflects the
  // real one
  val function =
    mirrorFunction.deepCopyWithSymbols().apply {
      name = callableId.callableName
      setDispatchReceiver(parentClass.thisReceiverOrFail.copyTo(this))
      // Point at the original class
      parent = parentClass
    }

  if (propertyName != null) {
    // Synthesize the property too
    mirrorFunction.factory.buildProperty {
      this.name = propertyName.asName()
    }.apply {
      parent = parentClass
      this.getter = function
      function.correspondingPropertySymbol = symbol
      // Read back the original offsets in the original source
      startOffset = constArgumentOfTypeAt<Int>(2)!!
      endOffset = constArgumentOfTypeAt<Int>(3)!!
    }
  } else {
    // Read back the original offsets in the original source
    startOffset = constArgumentOfTypeAt<Int>(2)!!
    endOffset = constArgumentOfTypeAt<Int>(3)!!
  }

  val annotations = sourceAnnotations ?: function.metroAnnotations(context.symbols.classIds)
  return IrCallableMetadata(
    callableId,
    mirrorFunction.callableId,
    annotations,
    isPropertyAccessor = propertyName != null,
    function,
    mirrorFunction,
  )
}
