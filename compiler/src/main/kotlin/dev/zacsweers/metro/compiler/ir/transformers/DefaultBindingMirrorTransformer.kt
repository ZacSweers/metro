// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.findAnnotations
import dev.zacsweers.metro.compiler.ir.linkDeclarationsInCompilation
import dev.zacsweers.metro.compiler.ir.nestedClassOrNull
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.name.ClassId

/**
 * Transforms DefaultBindingMirror classes generated in FIR by adding the `defaultBinding()` mirror
 * function whose return type encodes the default binding type from `@DefaultBinding<T>`.
 */
internal class DefaultBindingMirrorTransformer(context: IrMetroContext) :
  IrMetroContext by context, Lockable by Lockable() {
  private val cache = mutableMapOf<ClassId, Optional<IrType>>()

  /**
   * For a given class, returns the default binding type if it has a `@DefaultBinding` annotation
   * and a corresponding `DefaultBindingMirror` nested class.
   */
  fun visitClass(declaration: IrClass) {
    val _ = getOrComputeDefaultBindingType(null, declaration)
  }

  /**
   * For a given class, returns the default binding type if it has a `@DefaultBinding` annotation
   * and a corresponding `DefaultBindingMirror` nested class.
   */
  fun getOrComputeDefaultBindingType(
    caller: IrDeclarationWithName?,
    declaration: IrClass,
  ): IrType? {
    return cache
      .getOrPut(declaration.classIdOrFail) {
        val defaultBindingAnnotation =
          declaration.findAnnotations(metroSymbols.classIds.defaultBindingAnnotation).singleOrNull()
            ?: return@getOrPut Optional.empty()

        val mirrorClass =
          declaration.nestedClassOrNull(Symbols.Names.DefaultBindingMirrorClass)
            ?: return@getOrPut Optional.empty()

        val defaultBindingType =
          resolveDefaultBindingType(caller, mirrorClass, defaultBindingAnnotation)
        Optional.ofNullable(defaultBindingType)
      }
      .getOrNull()
  }

  /**
   * Tracks an IC lookup from [callingDeclaration] to [defaultBindingClass]'s DefaultBindingMirror.
   * This ensures that if `@DefaultBinding` changes on the supertype, dependents recompile.
   */
  fun trackDefaultBindingLookup(callingDeclaration: IrDeclaration, defaultBindingClass: IrClass) {
    val mirrorClassId =
      defaultBindingClass.classIdOrFail.createNestedClassId(Symbols.Names.DefaultBindingMirrorClass)
    trackClassLookup(callingDeclaration, mirrorClassId)
    // Also link the compilation units so structural changes are detected
    linkDeclarationsInCompilation(callingDeclaration, defaultBindingClass)
  }

  private fun resolveDefaultBindingType(
    caller: IrDeclarationWithName?,
    mirrorClass: IrClass,
    defaultBindingAnnotation: IrConstructorCall,
  ): IrType {
    val function = resolveDefaultBindingFunction(mirrorClass, defaultBindingAnnotation)
    // IC for changes
    caller?.let { with(metroContext) { trackFunctionCall(caller, function) } }
    return function.returnType
  }

  private fun resolveDefaultBindingFunction(
    mirrorClass: IrClass,
    defaultBindingAnnotation: IrConstructorCall,
  ): IrSimpleFunction {
    mirrorClass.getSimpleFunction(Symbols.Names.defaultBindingFunction.asString())?.owner?.let {
      // External or already generated
      return it
    }

    val bindingType = defaultBindingAnnotation.typeArguments.single()!! // Checked in FIR

    checkNotLocked()

    // Generate the defaultBinding() function in the mirror class
    return mirrorClass
      .addFunction(
        Symbols.Names.defaultBindingFunction.asString(),
        returnType = bindingType,
        modality = Modality.ABSTRACT,
        origin = Origins.Default,
      )
      .apply {
        // Register as metadata visible
        metadataDeclarationRegistrarCompat.registerFunctionAsMetadataVisible(this)
      }
  }
}
