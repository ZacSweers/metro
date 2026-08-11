// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.compat.propertyIfAccessorCompat
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.proto.SignatureCarrier
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
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/** Representation of the `@CallableMetadata` annotation contents. */
@Poko
internal class IrCallableMetadata(
  val callableId: CallableId,
  val signatureCallableId: CallableId,
  val annotations: MetroAnnotations<IrAnnotation>,
  val isPropertyAccessor: Boolean,
  /** The name for the generated newInstance function. */
  val newInstanceName: Name?,
  @Poko.Skip val function: IrSimpleFunction,
  @Poko.Skip val signatureFunction: IrSimpleFunction,
) {
  companion object {
    /**
     * Creates an [IrCallableMetadata] for in-compilation scenarios where we already have direct
     * access to the source function. This avoids the round-trip through the `@CallableMetadata`
     * annotation that external compilations require.
     */
    fun forInCompilation(
      sourceFunction: IrSimpleFunction,
      signatureFunction: IrSimpleFunction,
      annotations: MetroAnnotations<IrAnnotation>,
      isPropertyAccessor: Boolean,
      newInstanceName: Name,
    ): IrCallableMetadata {
      val callableId =
        if (isPropertyAccessor) {
          sourceFunction.propertyIfAccessorCompat.expectAs<IrProperty>().callableId
        } else {
          sourceFunction.callableId
        }
      return IrCallableMetadata(
        callableId = callableId,
        signatureCallableId = signatureFunction.callableId,
        annotations = annotations,
        isPropertyAccessor = isPropertyAccessor,
        newInstanceName = newInstanceName,
        function = sourceFunction,
        signatureFunction = signatureFunction,
      )
    }
  }
}

context(context: IrMetroContext)
internal fun IrSimpleFunction.irCallableMetadata(
  sourceAnnotations: MetroAnnotations<IrAnnotation>?,
  isInterop: Boolean,
): IrCallableMetadata {
  return propertyIfAccessorCompat.irCallableMetadata(this, sourceAnnotations, isInterop)
}

context(context: IrMetroContext)
internal fun IrAnnotationContainer.irCallableMetadata(
  signatureFunction: IrSimpleFunction,
  sourceAnnotations: MetroAnnotations<IrAnnotation>?,
  isInterop: Boolean,
  signatureCarrier: SignatureCarrier = SignatureCarrier.MIRROR_FUNCTION,
): IrCallableMetadata {
  if (isInterop) {
    return IrCallableMetadata(
      callableId = signatureFunction.callableId,
      signatureCallableId = signatureFunction.callableId,
      annotations =
        sourceAnnotations ?: signatureFunction.metroAnnotations(context.metroSymbols.classIds),
      isPropertyAccessor = signatureFunction.isPropertyAccessor,
      newInstanceName = signatureFunction.name,
      function = signatureFunction,
      signatureFunction = signatureFunction,
    )
  }

  val callableMetadataAnno =
    getAnnotation(Symbols.FqNames.CallableMetadataClass)
      ?: reportCompilerBug(
        "No @CallableMetadata found on ${this.expectAsOrNull<IrDeclarationParent>()?.kotlinFqName}"
      )
  return callableMetadataAnno.toIrCallableMetadata(
    signatureFunction,
    sourceAnnotations,
    signatureCarrier,
  )
}

context(context: IrMetroContext)
internal fun IrConstructorCall.toIrCallableMetadata(
  signatureFunction: IrSimpleFunction,
  sourceAnnotations: MetroAnnotations<IrAnnotation>?,
  signatureCarrier: SignatureCarrier = SignatureCarrier.MIRROR_FUNCTION,
): IrCallableMetadata {
  val signatureParent = signatureFunction.parentAsClass
  val clazz =
    if (signatureCarrier == SignatureCarrier.CREATOR_FUNCTION && signatureParent.isCompanion) {
      signatureParent.parentAsClass
    } else {
      signatureParent
    }
  val parentClass = clazz.parentAsClass
  val callableName = getAnnotationStringValue("callableName")
  val propertyName = getAnnotationStringValue("propertyName")
  // Read back the original offsets in the original source
  val annoStartOffset = constArgumentOfTypeAt<Int>(2)!!
  val annoEndOffset = constArgumentOfTypeAt<Int>(3)!!
  val newInstanceName = constArgumentOfTypeAt<String>(4)?.asName()
  val sourceCallableName = propertyName.ifBlank { callableName }.asName()
  val callableId =
    CallableId(
      clazz.classIdOrFail.outerClassId!!,
      sourceCallableName,
    )

  // Fake a reference to the real function by making a copy of its generated signature carrier.
  val function =
    signatureFunction.deepCopyWithSymbols().apply {
      // Property carriers are functions, so keep the getter name on the reconstructed function.
      name = callableName.asName()
      if (signatureCarrier == SignatureCarrier.CREATOR_FUNCTION && !parentClass.isObject) {
        val instanceParameter = regularParameters.firstOrNull()
        if (instanceParameter?.name != Symbols.Names.instance) {
          reportCompilerBug(
            "Expected an instance parameter on new-instance signature carrier for $callableId"
          )
        }
        parameters = parameters.filterNot { it === instanceParameter }
      }
      setDispatchReceiver(parentClass.thisReceiverOrFail.copyTo(this))
      // Point at the original class
      parent = parentClass
    }

  if (propertyName.isNotBlank()) {
    // Synthesize the property too
    signatureFunction.factory
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
    signatureCallableId = signatureFunction.callableId,
    annotations = annotations,
    isPropertyAccessor = propertyName.isNotBlank(),
    newInstanceName = newInstanceName,
    function = function,
    signatureFunction = signatureFunction,
  )
}
