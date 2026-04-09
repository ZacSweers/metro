// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.findAnnotations
import dev.zacsweers.metro.compiler.ir.linkDeclarationsInCompilation
import dev.zacsweers.metro.compiler.ir.nestedClassOrNull
import dev.zacsweers.metro.compiler.ir.qualifierAnnotation
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
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId

/**
 * Transforms DefaultBindingMirror classes generated in FIR by adding the `defaultBinding()` mirror
 * function whose return type encodes the default binding type from `@DefaultBinding<T>`.
 */
internal class DefaultBindingMirrorTransformer(context: IrMetroContext) :
  IrMetroContext by context, Lockable by Lockable() {
  private val cache = mutableMapOf<ClassId, Optional<IrTypeKey>>()

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
  ): IrTypeKey? {
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
  ): IrTypeKey {
    val (function, key) = resolveDefaultBindingFunction(mirrorClass, defaultBindingAnnotation)
    // IC for changes
    caller?.let { with(metroContext) { trackFunctionCall(caller, function) } }
    return key
  }

  private fun resolveDefaultBindingFunction(
    mirrorClass: IrClass,
    defaultBindingAnnotation: IrConstructorCall,
  ): Pair<IrSimpleFunction, IrTypeKey> {
    mirrorClass.getSimpleFunction(Symbols.Names.defaultBindingFunction.asString())?.owner?.let {
      // External or already generated
      return it to IrContextualTypeKey.from(it).typeKey
    }

    val bindingType = defaultBindingAnnotation.typeArguments.single()!! // Checked in FIR

    checkNotLocked()

    // Copy qualifier annotation from the type arg or the @DefaultBinding-annotated class
    val qualifier =
      with(metroContext) {
        bindingType.qualifierAnnotation() ?: mirrorClass.parentAsClass.qualifierAnnotation()
      }
    // Remove the qualifier if present here
    val finalType = bindingType.removeAnnotations { anno -> anno == qualifier?.ir }

    // Generate the defaultBinding() function in the mirror class
    val function =
      mirrorClass
        .addFunction(
          Symbols.Names.defaultBindingFunction.asString(),
          returnType = finalType,
          modality = Modality.ABSTRACT,
          origin = Origins.Default,
        )
        .apply {
          qualifier?.let { annotations += it.ir.deepCopyWithSymbols() }
          // Register as metadata visible
          metadataDeclarationRegistrarCompat.registerFunctionAsMetadataVisible(this)
        }
    return function to IrTypeKey(finalType, qualifier)
  }
}
