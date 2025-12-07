// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.getAnnotationStringValue
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/** Representation of the `@CallableMetadata` annotation contents. */
@Poko
internal class IrCallableMetadata(
  val callableId: CallableId,
  val mirrorCallableId: CallableId,
  val annotations: MetroAnnotations<IrAnnotation>,
  val isPropertyAccessor: Boolean,
  /** The name for the generated newInstance function. */
  val newInstanceName: Name?,
  @Poko.Skip val function: IrSimpleFunction,
  @Poko.Skip val mirrorFunction: IrSimpleFunction,
) {
  companion object {
    /**
     * Creates an [IrCallableMetadata] for in-compilation scenarios where we already have direct
     * access to the source function. This avoids the round-trip through the `@CallableMetadata`
     * annotation that external compilations require.
     */
    fun forInCompilation(
      sourceFunction: IrSimpleFunction,
      mirrorFunction: IrSimpleFunction,
      annotations: MetroAnnotations<IrAnnotation>,
      isPropertyAccessor: Boolean,
    ): IrCallableMetadata {
      val callableId =
        if (isPropertyAccessor) {
          sourceFunction.propertyIfAccessor.expectAs<IrProperty>().callableId
        } else {
          sourceFunction.callableId
        }
      return IrCallableMetadata(
        callableId = callableId,
        mirrorCallableId = mirrorFunction.callableId,
        annotations = annotations,
        isPropertyAccessor = isPropertyAccessor,
        newInstanceName = sourceFunction.name,
        function = sourceFunction,
        mirrorFunction = mirrorFunction,
      )
    }
  }
}

context(context: IrMetroContext)
internal fun IrSimpleFunction.irCallableMetadata(
  sourceAnnotations: MetroAnnotations<IrAnnotation>?,
  isInterop: Boolean,
): IrCallableMetadata {
  return propertyIfAccessor.irCallableMetadata(this, sourceAnnotations, isInterop)
}

context(context: IrMetroContext)
internal fun IrAnnotationContainer.irCallableMetadata(
  mirrorFunction: IrSimpleFunction,
  sourceAnnotations: MetroAnnotations<IrAnnotation>?,
  isInterop: Boolean,
): IrCallableMetadata {
  if (isInterop) {
    return IrCallableMetadata(
      callableId = mirrorFunction.callableId,
      mirrorCallableId = mirrorFunction.callableId,
      annotations =
        sourceAnnotations ?: mirrorFunction.metroAnnotations(context.metroSymbols.classIds),
      isPropertyAccessor = mirrorFunction.isPropertyAccessor,
      newInstanceName = mirrorFunction.name,
      function = mirrorFunction,
      mirrorFunction = mirrorFunction,
    )
  }

  val callableMetadataAnno =
    getAnnotation(Symbols.FqNames.CallableMetadataClass)
      ?: reportCompilerBug(
        "No @CallableMetadata found on ${this.expectAsOrNull<IrDeclarationParent>()?.kotlinFqName}"
      )
  return callableMetadataAnno.toIrCallableMetadata(mirrorFunction, sourceAnnotations)
}

context(context: IrMetroContext)
internal fun IrConstructorCall.toIrCallableMetadata(
  mirrorFunction: IrSimpleFunction,
  sourceAnnotations: MetroAnnotations<IrAnnotation>?,
): IrCallableMetadata {
  val clazz = mirrorFunction.parentAsClass
  val parentClass = clazz.parentAsClass
  val callableName = getAnnotationStringValue("callableName")
  val propertyName = getAnnotationStringValue("propertyName")
  // Read back the original offsets in the original source
  val annoStartOffset = constArgumentOfTypeAt<Int>(2)!!
  val annoEndOffset = constArgumentOfTypeAt<Int>(3)!!
  val newInstanceName = constArgumentOfTypeAt<String>(4)?.asName()
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

  if (propertyName.isNotBlank()) {
    // Synthesize the property too
    mirrorFunction.factory
      .buildProperty {
        this.name = propertyName.asName()
        startOffset = annoStartOffset
        endOffset = annoEndOffset
      }
      .apply {
        parent = parentClass
        this.getter = function
        function.correspondingPropertySymbol = symbol
      }
  } else {
    function.startOffset = annoStartOffset
    function.endOffset = annoEndOffset
  }

  val annotations = sourceAnnotations ?: function.metroAnnotations(context.metroSymbols.classIds)
  return IrCallableMetadata(
    callableId = callableId,
    mirrorCallableId = mirrorFunction.callableId,
    annotations = annotations,
    isPropertyAccessor = propertyName.isNotBlank(),
    newInstanceName = newInstanceName,
    function = function,
    mirrorFunction = mirrorFunction,
  )
}
