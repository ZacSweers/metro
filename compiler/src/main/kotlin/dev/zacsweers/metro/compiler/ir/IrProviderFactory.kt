// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

internal sealed class ProviderFactory : IrMetroFactory, IrBindingContainerCallable {
  /**
   * The canonical typeKey for this provider. For `@IntoSet`/`@IntoMap` bindings, this includes the
   * unique `@MultibindingElement` qualifier. For non-multibinding providers, this equals
   * [rawTypeKey].
   */
  abstract override val typeKey: IrTypeKey
  abstract val contextualTypeKey: IrContextualTypeKey

  /** The raw return type key without multibinding transformation. Used for diagnostics. */
  abstract val rawTypeKey: IrTypeKey

  abstract val callableId: CallableId
  abstract val annotations: MetroAnnotations<IrAnnotation>
  abstract val parameters: Parameters
  abstract val isPropertyAccessor: Boolean
  /** The name for the generated newInstance function. */
  abstract val newInstanceName: Name
  abstract override val function: IrSimpleFunction

  /**
   * The class that contains this provider function. For instance methods, this is the graph or
   * binding container. For static/object methods, this is the object.
   */
  val providerParentClass: IrClass?
    get() = function.parentClassOrNull

  /** Returns true if the provider function requires a dispatch receiver (instance method). */
  val requiresDispatchReceiver: Boolean
    get() = function.dispatchReceiverParameter != null && providerParentClass?.isObject != true

  /**
   * Returns true if the provider can bypass factory instantiation. For Metro factories, this means
   * calling the original provider function or property. For Dagger factories, this means calling
   * the original provider function.
   */
  val canBypassFactory: Boolean
    // TODO what about !contextualTypeKey.isDeferrable?
    get() = true

  /**
   * Returns true if the original provides declaration can be called directly (not via factory
   * static method). This requires the function to be public and accessible.
   */
  override fun supportsDirectInvocation(from: IrDeclarationWithVisibility): Boolean {
    return when (val decl = realDeclaration) {
      // For Metro factories, we need to check the actual function's visibility
      // The `function` property is a copy that doesn't reflect transformed visibility,
      // so we look up the real function on the parent class
      is IrFunction -> decl.isVisibleTo(from)
      // TODO support fields
      else -> false
    }
  }

  class Metro(
    override val factoryClass: IrClass,
    override val typeKey: IrTypeKey,
    override val rawTypeKey: IrTypeKey,
    override val contextualTypeKey: IrContextualTypeKey,
    override val realDeclaration: IrDeclaration?,
    private val callableMetadata: IrCallableMetadata,
    parametersLazy: Lazy<Parameters>,
  ) : ProviderFactory() {
    val mirrorFunction: IrSimpleFunction
      get() = callableMetadata.mirrorFunction

    override val callableId: CallableId
      get() = callableMetadata.callableId

    override val function: IrSimpleFunction
      get() = callableMetadata.function

    override val annotations: MetroAnnotations<IrAnnotation>
      get() = callableMetadata.annotations

    override val isPropertyAccessor: Boolean
      get() = callableMetadata.isPropertyAccessor

    override val newInstanceName: Name
      get() =
        callableMetadata.newInstanceName
          ?: reportCompilerBug(
            "No newInstanceName present in CallableMetadata for provider factory for $callableId"
          )

    override val parameters by parametersLazy

    override val isDaggerFactory: Boolean = false
  }

  class Dagger(
    override val factoryClass: IrClass,
    override val typeKey: IrTypeKey,
    override val contextualTypeKey: IrContextualTypeKey,
    override val rawTypeKey: IrTypeKey,
    override val callableId: CallableId,
    override val annotations: MetroAnnotations<IrAnnotation>,
    override val parameters: Parameters,
    override val function: IrSimpleFunction,
    override val isPropertyAccessor: Boolean,
    override val newInstanceName: Name,
    override val realDeclaration: IrFunction,
  ) : ProviderFactory() {
    override val isDaggerFactory: Boolean = true
  }

  companion object {
    context(context: IrMetroContext)
    operator fun invoke(
      contextKey: IrContextualTypeKey,
      clazz: IrClass,
      mirrorFunction: IrSimpleFunction,
      sourceAnnotations: MetroAnnotations<IrAnnotation>?,
      callableMetadata: IrCallableMetadata,
      /** Pre-computed real declaration for in-compilation case. If null, will be looked up. */
      realDeclaration: IrDeclaration? = null,
    ): Metro {
      val rawTypeKey = contextKey.typeKey.copy(qualifier = callableMetadata.annotations.qualifier)
      val typeKey = rawTypeKey.transformMultiboundQualifier(callableMetadata.annotations)

      return Metro(
        factoryClass = clazz,
        typeKey = typeKey,
        contextualTypeKey = contextKey.withTypeKey(typeKey),
        rawTypeKey = rawTypeKey,
        callableMetadata = callableMetadata,
        realDeclaration =
          realDeclaration
            ?: lookupRealDeclaration(
              callableMetadata.isPropertyAccessor,
              callableMetadata.function,
            ),
        parametersLazy = memoize { callableMetadata.function.parameters() },
      )
    }

    context(context: IrMetroContext)
    fun lookupRealDeclaration(isPropertyAccessor: Boolean, function: IrFunction): IrDeclaration? {
      val parentClass = function.parentClassOrNull ?: return null
      return if (isPropertyAccessor) {
        parentClass.properties
          .find {
            it.name == function.name &&
              it.isAnnotatedWithAny(context.metroSymbols.classIds.providesAnnotations)
          }
          ?.let {
            val backingField = it.backingField
            if (backingField?.hasAnnotation(Symbols.ClassIds.JvmField) == true) {
              backingField
            } else {
              it.getter ?: it.backingField
            }
          }
      } else {
        parentClass.getSimpleFunction(function.name.asString())?.owner
      }
    }
  }
}
