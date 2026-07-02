// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWith
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
): IrClass {
  return nestedClasses.firstOrNull { it.origin == origin && it.name == name }
    ?: createMetadataVisibleHiddenNestedClass(
      name = name,
      origin = origin,
      kind = kind,
      superTypesProvider = superTypesProvider,
      copyTypeParameters = copyTypeParameters,
      isCompanion = isCompanion,
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
      parentClass.addChild(this)
      context.metadataDeclarationRegistrarCompat.registerClassAsMetadataVisible(this)
    }
}

context(context: IrMetroContext)
internal fun IrClass.getOrCreateGraphImplClassShell(): IrClass {
  nestedClassOrNull(Origins.GraphImplClassDeclaration)?.let {
    return it
  }

  val nameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  for (nested in nestedClasses) {
    nameAllocator.reserveName(nested.name.asString())
  }
  val creatorFunction =
    nestedClasses
      .singleOrNull {
        it.isAnnotatedWithAny(context.metroSymbols.dependencyGraphFactoryAnnotations)
      }
      ?.singleAbstractFunction()

  val parentClass = this
  return context.irFactory
    .buildClass {
      name = nameAllocator.newName(Symbols.Names.Impl.asString()).asName()
      origin = Origins.GraphImplClassDeclaration
      kind = ClassKind.CLASS
      visibility = DescriptorVisibilities.PUBLIC
      modality = Modality.FINAL
    }
    .apply {
      typeParameters = copyTypeParametersFrom(parentClass)
      createThisReceiverParameter()
      superTypes += parentClass.symbol.typeWith(typeParameters.map { it.defaultType })
      addDeprecatedHiddenAnnotation()
      addMetroImplMarkerAnnotation()
      parentClass.addChild(this)
      context.metadataDeclarationRegistrarCompat.registerClassAsMetadataVisible(this)
      if (primaryConstructor == null) {
        addConstructor {
          visibility = DescriptorVisibilities.PRIVATE
          isPrimary = true
          origin = Origins.Default
        }
          .apply {
            creatorFunction?.let {
              for (param in it.regularParameters) {
                addValueParameter(param.name, param.type).apply {
                  replaceAnnotationsCompat(param.annotationsCompat())
                }
              }
            }
            body = generateDefaultConstructorBody()
            context.metadataDeclarationRegistrarCompat.registerConstructorAsMetadataVisible(this)
          }
      }
    }
}

@IgnorableReturnValue
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
