// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.kClassReference
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

/** Shared metadata and annotations for Circuit classes generated in IR. */
internal class CircuitIrGenerationSupport(
  private val pluginContext: IrPluginContext,
  private val compatContext: CompatContext,
) : CompatContext by compatContext {
  private val builtinsFinder by lazy {
    with(compatContext) { pluginContext.finderForBuiltinsCompat() }
  }

  val metadataDeclarationRegistrarCompat: IrGeneratedDeclarationsRegistrarCompat by lazy {
    compatContext.createIrGeneratedDeclarationsRegistrar(pluginContext)
  }

  val irTypeSystemContext by lazy { IrTypeSystemContextImpl(pluginContext.irBuiltIns) }

  private val injectAnnotationCtor by lazy {
    builtinsFinder.findClass(Symbols.ClassIds.metroInject)!!.constructors.first()
  }

  private val contributesIntoSetAnnotationCtor by lazy {
    builtinsFinder.findClass(CONTRIBUTES_INTO_SET_CLASS_ID)!!.constructors.first()
  }

  private val originAnnotationCtor by lazy {
    builtinsFinder.findClass(Symbols.ClassIds.metroOrigin)!!.constructors.first()
  }

  private val deprecatedAnnotationCtor by lazy {
    builtinsFinder.findClass(StandardClassIds.Annotations.Deprecated)!!.constructors.first {
      it.owner.isPrimary
    }
  }

  private val deprecationLevel by lazy {
    builtinsFinder.findClass(StandardClassIds.DeprecationLevel)!!
  }

  private val hiddenDeprecationLevel by lazy {
    deprecationLevel.owner.declarations
      .filterIsInstance<IrEnumEntry>()
      .single { it.name.asString() == "HIDDEN" }
      .symbol
  }

  fun findBuiltinsClass(classId: ClassId): IrClassSymbol? = builtinsFinder.findClass(classId)

  fun addGeneratedClassAnnotations(
    generatedClass: IrClass,
    scopeClass: IrClassSymbol,
    originClass: IrClassSymbol?,
    qualifier: IrConstructorCall? = null,
  ) {
    generatedClass.addAnnotationCompat(
      context(pluginContext) { buildAnnotation(generatedClass.symbol, injectAnnotationCtor) }
    )
    generatedClass.addAnnotationCompat(
      context(pluginContext) {
        buildAnnotation(generatedClass.symbol, contributesIntoSetAnnotationCtor) { annotation ->
          annotation.arguments[0] = kClassReference(scopeClass)
        }
      }
    )
    qualifier?.let { generatedClass.addAnnotationCompat(it) }
    originClass?.let { addOriginAnnotation(generatedClass, it) }
  }

  fun addMetadataVisibleOrigin(generatedClass: IrClass, originClass: IrClassSymbol) {
    metadataDeclarationRegistrarCompat.addMetadataVisibleAnnotationsToElement(
      generatedClass,
      buildOriginAnnotation(generatedClass, originClass),
    )
  }

  fun markAsDeprecatedHidden(generatedClass: IrClass) {
    generatedClass.addAnnotationCompat(
      context(pluginContext) {
        buildAnnotation(generatedClass.symbol, deprecatedAnnotationCtor) { annotation ->
          annotation.arguments[0] =
            irString("This synthesized declaration should not be used directly")
          annotation.arguments[2] =
            IrGetEnumValueImpl(
              SYNTHETIC_OFFSET,
              SYNTHETIC_OFFSET,
              deprecationLevel.defaultType,
              hiddenDeprecationLevel,
            )
        }
      }
    )
  }

  private fun addOriginAnnotation(generatedClass: IrClass, originClass: IrClassSymbol) {
    generatedClass.addAnnotationCompat(buildOriginAnnotation(generatedClass, originClass))
  }

  private fun buildOriginAnnotation(
    generatedClass: IrClass,
    originClass: IrClassSymbol,
  ): IrConstructorCall {
    return context(pluginContext) {
      buildAnnotation(generatedClass.symbol, originAnnotationCtor) { annotation ->
        annotation.arguments[0] = kClassReference(originClass)
      }
    }
  }
}

private val CONTRIBUTES_INTO_SET_CLASS_ID =
  ClassId(Symbols.FqNames.metroRuntimePackage, Name.identifier("ContributesIntoSet"))
