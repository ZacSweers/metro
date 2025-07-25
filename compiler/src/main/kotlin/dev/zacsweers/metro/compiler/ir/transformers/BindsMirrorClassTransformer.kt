// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.MultibindsCallable
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.metroFunctionOf
import dev.zacsweers.metro.compiler.ir.nestedClassOrNull
import dev.zacsweers.metro.compiler.ir.toBindsCallable
import dev.zacsweers.metro.compiler.ir.toMultibindsCallable
import dev.zacsweers.metro.compiler.mirrorIrConstructorCalls
import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyParametersFrom
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

/**
 * Transforms binding mirror classes generated in FIR by adding mirror functions for `@Binds` and
 * `@Multibinds` declarations.
 */
internal class BindsMirrorClassTransformer(context: IrMetroContext) : IrMetroContext by context {
  private val cache = mutableMapOf<ClassId, Optional<BindsMirror>>()

  // When we generate binds/providers we need to genreate a mirror class too
  fun getOrComputeBindsMirror(declaration: IrClass): BindsMirror? {
    return cache
      .getOrPut(declaration.classIdOrFail) {
        val mirrorClass = declaration.nestedClassOrNull(Symbols.Names.BindsMirrorClass)
        val mirror =
          if (mirrorClass == null) {
            // If there's no mirror class, there's no bindings
            // TODO what if they forgot to run the metro compiler? Should we put something in
            //  metadata?
            return@getOrPut Optional.empty()
          } else {
            transformBindingMirrorClass(declaration, mirrorClass)
          }
        Optional.ofNullable(mirror)
      }
      .getOrNull()
  }
}

internal data class BindsMirror(
  val ir: IrClass,
  /** Set of binds callables by their [CallableId]. */
  val bindsCallables: Set<BindsCallable>,
  /** Set of multibinds callables by their [BindsCallable]. */
  val multibindsCallables: Set<MultibindsCallable>,
) {
  fun isEmpty() = bindsCallables.isEmpty() && multibindsCallables.isEmpty()
}

context(context: IrMetroContext)
private fun transformBindingMirrorClass(parentClass: IrClass, mirrorClass: IrClass): BindsMirror {
  val isExternal = mirrorClass.isExternalParent
  // Find all @Binds and @Multibinds declarations in the parent class
  val bindsCallables = mutableSetOf<BindsCallable>()
  val multibindsCallables = mutableSetOf<MultibindsCallable>()

  fun processFunction(declaration: IrSimpleFunction) {
    if (!declaration.isFakeOverride) {
      val metroFunction = metroFunctionOf(declaration)
      if (metroFunction.annotations.isBinds || metroFunction.annotations.isMultibinds) {
        // TODO we round-trip generating -> reading back. Should we optimize that path?
        val function =
          if (isExternal) metroFunction else generateMirrorFunction(mirrorClass, metroFunction)
        if (metroFunction.annotations.isBinds) {
          bindsCallables += function.toBindsCallable()
        } else {
          multibindsCallables += function.toMultibindsCallable()
        }
      }
    }
  }

  // If external, just read the mirror class directly. If current round, transform the parent and
  // generate its mirrors
  val classToProcess = if (isExternal) mirrorClass else parentClass
  classToProcess.declarations.forEach { declaration ->
    when (declaration) {
      is IrProperty -> {
        val getter = declaration.getter ?: return@forEach
        processFunction(getter)
      }
      is IrSimpleFunction -> processFunction(declaration)
    }
  }

  val bindsMirror = BindsMirror(mirrorClass, bindsCallables, multibindsCallables)
  return bindsMirror
}

context(context: IrMetroContext)
private fun generateMirrorFunction(
  mirrorClass: IrClass,
  targetFunction: MetroSimpleFunction,
): MetroSimpleFunction {
  // Create a unique name for this mirror function based on the target function name
  // and qualifier + map key annotations
  val annotations = targetFunction.annotations
  val mirrorFunctionName =
    buildString {
        append(targetFunction.ir.propertyIfAccessor.expectAs<IrDeclarationWithName>().name)
        annotations.qualifier?.hashCode()?.toUInt()?.let(::append)
        annotations.mapKeys.firstOrNull()?.hashCode()?.toUInt()?.let(::append)
        annotations.multibinds?.hashCode()?.toUInt()?.let(::append)

        if (annotations.isIntoSet) {
          append("_intoset")
        } else if (annotations.isElementsIntoSet) {
          append("_elementsintoset")
        } else if (annotations.isIntoMap) {
          append("_intomap")
        }
      }
      .asName()

  val mirrorFunction =
    mirrorClass
      .addFunction {
        updateFrom(targetFunction.ir)
        name = mirrorFunctionName
        visibility = DescriptorVisibilities.PUBLIC
        returnType = targetFunction.ir.returnType
        origin = Origins.Default
        modality = Modality.ABSTRACT
      }
      .apply {
        copyParametersFrom(targetFunction.ir)
        this.annotations = annotations.mirrorIrConstructorCalls(symbol)
      }

  val callableMetadata =
    buildAnnotation(mirrorFunction.symbol, context.symbols.callableMetadataAnnotationConstructor) {
      with(it) {
        // callableName
        arguments[0] = irString(targetFunction.callableId.callableName.asString())
        // isPropertyAccessor
        arguments[1] = irBoolean(targetFunction.ir.isPropertyAccessor)

        // TODO these locations are bogus in generated binding functions. Report origin class
        //  instead somewhere?
        // startOffset
        arguments[2] = irInt(targetFunction.ir.startOffset)
        // endOffset
        arguments[3] = irInt(targetFunction.ir.endOffset)
      }
    }

  mirrorFunction.annotations += callableMetadata

  // Register as metadata visible
  context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(mirrorFunction)
  return metroFunctionOf(mirrorFunction)
}
