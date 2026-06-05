// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Origins
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

context(context: IrMetroContext)
internal fun IrClass.getOrCreateMetadataVisibleHiddenNestedClass(
  name: Name,
  origin: IrDeclarationOrigin,
  kind: ClassKind = ClassKind.CLASS,
  superTypesProvider: IrClass.() -> List<IrType> = { emptyList() },
  copyTypeParameters: Boolean = true,
  isCompanion: Boolean = false,
  markAsMetroImpl: Boolean = false,
): IrClass {
  return nestedClasses.firstOrNull { it.origin == origin && it.name == name }
    ?: createMetadataVisibleHiddenNestedClass(
      name = name,
      origin = origin,
      kind = kind,
      superTypesProvider = superTypesProvider,
      copyTypeParameters = copyTypeParameters,
      isCompanion = isCompanion,
      markAsMetroImpl = markAsMetroImpl,
    )
}

context(context: IrMetroContext)
internal fun IrClass.createMetadataVisibleHiddenNestedClass(
  name: Name,
  origin: IrDeclarationOrigin,
  kind: ClassKind = ClassKind.CLASS,
  superTypesProvider: IrClass.() -> List<IrType> = { emptyList() },
  copyTypeParameters: Boolean = true,
  isCompanion: Boolean = false,
  markAsMetroImpl: Boolean = false,
): IrClass {
  val parentClass = this
  return context.irFactory
    .buildClass {
      this.name = name
      this.origin = origin
      this.kind = kind
      this.visibility = DescriptorVisibilities.PUBLIC
      this.modality = Modality.FINAL
      this.isCompanion = isCompanion
    }
    .apply {
      if (copyTypeParameters) {
        typeParameters = copyTypeParametersFrom(parentClass)
      }
      createThisReceiverParameter()
      superTypes += superTypesProvider()
      addDeprecatedHiddenAnnotation()
      if (markAsMetroImpl) {
        addMetroImplMarkerAnnotation()
      }
      parentClass.addChild(this)
      context.metadataDeclarationRegistrarCompat.registerClassAsMetadataVisible(this)
    }
}

context(context: IrMetroContext)
internal fun IrClass.addMetadataVisibleHiddenCompanionObject(): IrClass {
  return getOrCreateMetadataVisibleHiddenNestedClass(
      name = SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT,
      origin = Origins.Default,
      kind = ClassKind.OBJECT,
      copyTypeParameters = false,
      isCompanion = true,
    )
    .apply {
      if (primaryConstructor == null) {
        addMetadataVisibleDefaultConstructor()
      }
    }
}

context(context: IrMetroContext)
internal fun IrClass.addMetadataVisibleDefaultConstructor() {
  if (primaryConstructor != null) return
  addSimpleDelegatingConstructor(
      context.irBuiltIns.anyClass.owner.primaryConstructor!!,
      context.irBuiltIns,
      isPrimary = true,
    )
    .apply {
      visibility = DescriptorVisibilities.PRIVATE
      context.metadataDeclarationRegistrarCompat.registerConstructorAsMetadataVisible(this)
    }
}

context(context: IrMetroContext)
private fun IrClass.addDeprecatedHiddenAnnotation() {
  addAnnotationCompat(
    buildAnnotation(symbol, context.metroSymbols.deprecatedAnnotationConstructor) { annotation ->
      annotation.arguments[0] = irString("This synthesized declaration should not be used directly")
      annotation.arguments[2] =
        IrGetEnumValueImpl(
          SYNTHETIC_OFFSET,
          SYNTHETIC_OFFSET,
          context.metroSymbols.deprecationLevel.defaultType,
          context.metroSymbols.hiddenDeprecationLevel,
        )
    }
  )
}

context(context: IrMetroContext)
private fun IrClass.addMetroImplMarkerAnnotation() {
  addAnnotationCompat(buildAnnotation(symbol, context.metroSymbols.metroImplMarkerConstructor))
}
