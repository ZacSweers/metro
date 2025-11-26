// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.name.CallableId

internal sealed interface ProviderFactory : IrMetroFactory, IrBindingContainerCallable {
  /**
   * The canonical typeKey for this provider. For `@IntoSet`/`@IntoMap` bindings, this includes the
   * unique `@MultibindingElement` qualifier. For non-multibinding providers, this equals
   * [rawTypeKey].
   */
  override val typeKey: IrTypeKey

  /** The raw return type key without multibinding transformation. Used for diagnostics. */
  val rawTypeKey: IrTypeKey

  val callableId: CallableId
  val annotations: MetroAnnotations<IrAnnotation>
  val parameters: Parameters
  val isPropertyAccessor: Boolean
  override val function: IrSimpleFunction

  class Metro(
    override val factoryClass: IrClass,
    override val typeKey: IrTypeKey,
    override val rawTypeKey: IrTypeKey,
    private val callableMetadata: IrCallableMetadata,
    parametersLazy: Lazy<Parameters>,
  ) : ProviderFactory {
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

    override val parameters by parametersLazy

    override val isDaggerFactory: Boolean = false
  }

  class Dagger(
    override val factoryClass: IrClass,
    override val typeKey: IrTypeKey,
    override val rawTypeKey: IrTypeKey,
    override val callableId: CallableId,
    override val annotations: MetroAnnotations<IrAnnotation>,
    override val parameters: Parameters,
    override val function: IrSimpleFunction,
    override val isPropertyAccessor: Boolean,
  ) : ProviderFactory {
    override val isDaggerFactory: Boolean = true
  }

  companion object {
    context(context: IrMetroContext)
    operator fun invoke(
      sourceTypeKey: IrTypeKey,
      clazz: IrClass,
      mirrorFunction: IrSimpleFunction,
      sourceAnnotations: MetroAnnotations<IrAnnotation>?,
      callableMetadata: IrCallableMetadata,
    ): Metro {
      val rawTypeKey = sourceTypeKey.copy(qualifier = callableMetadata.annotations.qualifier)
      val typeKey = rawTypeKey.transformMultiboundQualifier(callableMetadata.annotations)

      return Metro(
        factoryClass = clazz,
        typeKey = typeKey,
        rawTypeKey = rawTypeKey,
        callableMetadata = callableMetadata,
        parametersLazy = memoize { callableMetadata.function.parameters() },
      )
    }
  }
}
