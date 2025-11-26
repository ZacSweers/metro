// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.symbols

import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.allSupertypesSequence
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Represents a provider/lazy framework that can convert to/from Metro's canonical types.
 *
 * Think of this like a Moshi JSON adapter - every framework knows how to convert its types to/from
 * Metro Provider (the canonical representation).
 *
 * To add a new framework:
 * 1. Implement this interface
 * 2. Provide ClassIds for your provider/lazy types
 * 3. Implement conversion to/from Metro Provider
 * 4. Add to list passed into [ProviderTypeConverter.frameworks]
 */
internal sealed interface ProviderFramework {
  /** Returns true if this framework handles the given ClassId. */
  fun isApplicable(classId: ClassId): Boolean

  /**
   * Handles conversion within the same framework (e.g., Provider -> Lazy in the same framework).
   *
   * This is a fast path optimization when source and target are from the same framework.
   */
  context(context: IrMetroContext, scope: IrBuilderWithScope)
  fun IrExpression.handleSameFramework(targetKey: IrContextualTypeKey): IrExpression

  /** Converts a Metro provider to this framework's equivalent type. */
  context(context: IrMetroContext, scope: IrBuilderWithScope)
  fun fromMetroProvider(provider: IrExpression, targetKey: IrContextualTypeKey): IrExpression

  /** Converts this framework's provider to a Metro provider. */
  context(context: IrMetroContext, scope: IrBuilderWithScope)
  fun IrExpression.toMetroProvider(providerType: IrType): IrExpression

  /**
   * Creates a `Lazy` from a provider expression in this framework.
   *
   * [this] may be from any framework.
   */
  context(context: IrMetroContext, scope: IrBuilderWithScope)
  fun IrExpression.toLazy(targetKey: IrContextualTypeKey): IrExpression
}

/**
 * Metro's provider framework - the canonical representation.
 *
 * All conversions route through Metro Provider, so this framework has the simplest implementation -
 * mostly no-ops for conversions.
 */
internal class MetroProviderFramework(private val metroFrameworkSymbols: MetroFrameworkSymbols) :
  ProviderFramework {

  private val kotlinLazyClassId = ClassId(FqName("kotlin"), "Lazy".asName())

  override fun isApplicable(classId: ClassId): Boolean {
    return classId == metroFrameworkSymbols.canonicalProviderType.owner.classId ||
      classId == kotlinLazyClassId
  }

  context(_: IrMetroContext, _: IrBuilderWithScope)
  override fun IrExpression.handleSameFramework(targetKey: IrContextualTypeKey): IrExpression {
    val provider = this@handleSameFramework
    val targetClassId = targetKey.rawType?.classOrNull?.owner?.classId

    // Metro Provider -> Kotlin Lazy
    if (targetClassId == kotlinLazyClassId) {
      return provider.toLazy(targetKey)
    }

    // Otherwise, no conversion needed (Provider -> Provider)
    return provider
  }

  context(_: IrMetroContext, _: IrBuilderWithScope)
  override fun fromMetroProvider(
    provider: IrExpression,
    targetKey: IrContextualTypeKey,
  ): IrExpression {
    val targetClassId = targetKey.rawType?.classOrNull?.owner?.classId

    // Metro Provider -> Kotlin Lazy
    if (targetClassId == kotlinLazyClassId) {
      return provider.toLazy(targetKey)
    }

    // Metro Provider -> Metro Provider (no conversion)
    return provider
  }

  context(_: IrMetroContext, _: IrBuilderWithScope)
  override fun IrExpression.toMetroProvider(providerType: IrType): IrExpression {
    // Already a Metro provider - no conversion needed
    return this
  }

  context(_: IrMetroContext, scope: IrBuilderWithScope)
  override fun IrExpression.toLazy(targetKey: IrContextualTypeKey): IrExpression =
    with(scope) {
      val provider = this@toLazy
      return irInvoke(
        dispatchReceiver = irGetObject(metroFrameworkSymbols.doubleCheckCompanionObject),
        callee = metroFrameworkSymbols.doubleCheckLazy,
        args = listOf(provider),
        typeHint = targetKey.toIrType(),
        typeArgs = listOf(provider.type, targetKey.typeKey.type),
      )
    }
}

/** Javax: `javax.inject.Provider` */
internal class JavaxProviderFramework(private val symbols: JavaxSymbols) : ProviderFramework {

  override fun isApplicable(classId: ClassId): Boolean {
    return classId == JavaxSymbols.ClassIds.JAVAX_PROVIDER_CLASS_ID
  }

  context(_: IrMetroContext, _: IrBuilderWithScope)
  override fun IrExpression.handleSameFramework(targetKey: IrContextualTypeKey): IrExpression {
    // Same type, no conversion needed
    return this
  }

  context(_: IrMetroContext, scope: IrBuilderWithScope)
  override fun fromMetroProvider(
    provider: IrExpression,
    targetKey: IrContextualTypeKey,
  ): IrExpression =
    with(scope) {
      return irInvoke(
        extensionReceiver = provider,
        callee = symbols.asJavaxProvider,
        typeArgs = listOf(targetKey.typeKey.type),
      )
    }

  context(_: IrMetroContext, scope: IrBuilderWithScope)
  override fun IrExpression.toMetroProvider(providerType: IrType): IrExpression =
    with(scope) {
      val provider = this@toMetroProvider
      // Extract the value type from the provider type
      val valueType =
        providerType.requireSimpleType().arguments[0].typeOrNull
          ?: reportCompilerBug(
            "Provider type missing type argument: ${providerType.dumpKotlinLike()}"
          )

      return irInvoke(
        extensionReceiver = provider,
        callee = symbols.asMetroProvider,
        typeArgs = listOf(valueType),
      )
    }

  context(_: IrMetroContext, _: IrBuilderWithScope)
  override fun IrExpression.toLazy(targetKey: IrContextualTypeKey): IrExpression {
    // Javax has no Lazy concept, this should be handled by another interop
    reportCompilerBug(
      "Javax providers do not support lazy without Dagger interop enabled. " +
        "Enable Dagger interop to use Lazy with javax.inject.Provider."
    )
  }
}

/** Jakarta: `jakarta.inject.Provider` */
internal class JakartaProviderFramework(private val symbols: JakartaSymbols) : ProviderFramework {

  override fun isApplicable(classId: ClassId): Boolean {
    return classId == JakartaSymbols.ClassIds.JAKARTA_PROVIDER_CLASS_ID
  }

  context(_: IrMetroContext, _: IrBuilderWithScope)
  override fun IrExpression.handleSameFramework(targetKey: IrContextualTypeKey): IrExpression {
    // Same type, no conversion needed
    return this
  }

  context(_: IrMetroContext, scope: IrBuilderWithScope)
  override fun fromMetroProvider(
    provider: IrExpression,
    targetKey: IrContextualTypeKey,
  ): IrExpression =
    with(scope) {
      return irInvoke(
        extensionReceiver = provider,
        callee = symbols.asJakartaProvider,
        typeArgs = listOf(targetKey.typeKey.type),
      )
    }

  context(_: IrMetroContext, scope: IrBuilderWithScope)
  override fun IrExpression.toMetroProvider(providerType: IrType): IrExpression =
    with(scope) {
      val provider = this@toMetroProvider
      // Extract the value type from the provider type
      val valueType =
        providerType.requireSimpleType().arguments[0].typeOrNull
          ?: reportCompilerBug(
            "Provider type missing type argument: ${providerType.dumpKotlinLike()}"
          )

      return irInvoke(
        extensionReceiver = provider,
        callee = symbols.asMetroProvider,
        typeArgs = listOf(valueType),
      )
    }

  context(_: IrMetroContext, _: IrBuilderWithScope)
  override fun IrExpression.toLazy(targetKey: IrContextualTypeKey): IrExpression {
    // Javax has no Lazy concept, this should be handled by another interop
    reportCompilerBug(
      "Jakarta providers do not support lazy without Dagger interop enabled. " +
        "Enable Dagger interop to use Lazy with jakarta.inject.Provider."
    )
  }
}

/**
 * Guice:
 * - `com.google.inject.Provider`
 * - Conversion to Kotlin Lazy (via `GuiceInteropDoubleCheck`)
 */
internal class GuiceProviderFramework(
  private val symbols: GuiceSymbols,
  delegates: List<ProviderFramework>,
) : BaseDelegatingProviderFramework(delegates) {

  private val kotlinLazyClassId = ClassId(FqName("kotlin"), "Lazy".asName())

  private val lazyFromGuiceProvider by lazy {
    symbols.guiceDoubleCheckCompanionObject.requireSimpleFunction("lazyFromGuiceProvider")
  }

  private val lazyFromMetroProvider by lazy {
    symbols.guiceDoubleCheckCompanionObject.requireSimpleFunction("lazyFromMetroProvider")
  }

  override fun isApplicable(classId: ClassId): Boolean {
    return classId == GuiceSymbols.ClassIds.provider
  }

  context(_: IrMetroContext, _: IrBuilderWithScope)
  override fun IrExpression.handleSameFramework(targetKey: IrContextualTypeKey): IrExpression {
    // Same type, no conversion needed
    return this
  }

  context(_: IrMetroContext, scope: IrBuilderWithScope)
  override fun fromMetroProvider(
    provider: IrExpression,
    targetKey: IrContextualTypeKey,
  ): IrExpression =
    with(scope) {
      val targetClassId = targetKey.rawType?.classOrNull?.owner?.classId

      // Handle Guice's own provider type
      if (targetClassId == GuiceSymbols.ClassIds.provider) {
        return irInvoke(
          extensionReceiver = provider,
          callee = symbols.asGuiceProvider,
          typeArgs = listOf(targetKey.typeKey.type),
        )
      }

      // Delegate to base class for javax/jakarta
      return super.fromMetroProvider(provider, targetKey)
    }

  context(_: IrMetroContext, scope: IrBuilderWithScope)
  override fun IrExpression.toMetroProvider(providerType: IrType): IrExpression =
    with(scope) {
      val provider = this@toMetroProvider
      // Extract the value type from the provider type
      val valueType =
        (providerType as IrSimpleType).arguments[0].typeOrNull
          ?: reportCompilerBug(
            "Provider type missing type argument: ${providerType.dumpKotlinLike()}"
          )

      return irInvoke(
        extensionReceiver = provider,
        callee = symbols.asMetroProvider,
        typeArgs = listOf(valueType),
      )
    }

  context(_: IrMetroContext, scope: IrBuilderWithScope)
  override fun IrExpression.toLazy(targetKey: IrContextualTypeKey): IrExpression =
    with(scope) {
      val provider = this@toLazy
      val targetClassId = targetKey.rawType?.classOrNull?.owner?.classId

      // Only support conversion to Kotlin Lazy
      if (targetClassId != kotlinLazyClassId) {
        reportCompilerBug(
          "Guice providers only support conversion to Kotlin Lazy, not $targetClassId"
        )
      }

      // Determine which lazy function to use based on the provider type
      val lazyFunction =
        provider.type.rawTypeOrNull()?.let { rawType ->
          rawType
            .allSupertypesSequence(excludeSelf = false, excludeAny = true)
            .firstNotNullOfOrNull { type ->
              when (type.classOrNull?.owner?.classId) {
                GuiceSymbols.ClassIds.provider -> lazyFromGuiceProvider
                Symbols.ClassIds.metroProvider -> lazyFromMetroProvider
                else -> null
              }
            }
        } ?: reportCompilerBug("Unexpected provider type: ${provider.type.dumpKotlinLike()}")

      return irInvoke(
        dispatchReceiver = irGetObject(symbols.guiceDoubleCheckCompanionObject),
        callee = lazyFunction,
        args = listOf(provider),
        typeHint = targetKey.toIrType(),
        typeArgs = listOf(provider.type, targetKey.typeKey.type),
      )
    }
}

/** Base class for frameworks that support delegation to javax/jakarta providers. */
internal abstract class BaseDelegatingProviderFramework(
  protected val delegates: List<ProviderFramework>
) : ProviderFramework {

  /**
   * Default implementation that delegates to one of the delegate frameworks. Subclasses should
   * handle their own types first, then call super for delegation.
   */
  context(_: IrMetroContext, scope: IrBuilderWithScope)
  override fun fromMetroProvider(
    provider: IrExpression,
    targetKey: IrContextualTypeKey,
  ): IrExpression =
    with(scope) {
      val targetClassId =
        targetKey.rawType?.classOrNull?.owner?.classId
          ?: reportCompilerBug("Missing target class ID for $targetKey")

      delegates
        .find { it.isApplicable(targetClassId) }
        ?.let { delegate ->
          return with(delegate) { fromMetroProvider(provider, targetKey) }
        }

      reportCompilerBug("No delegate found for target type $targetClassId")
    }
}

/**
 * Dagger:
 * - `dagger.Lazy`
 * - `dagger.internal.Provider`
 */
internal class DaggerProviderFramework(
  private val symbols: DaggerSymbols,
  delegates: List<ProviderFramework>,
) : BaseDelegatingProviderFramework(delegates) {

  // Lazy creation functions for different provider types
  private val lazyFromDaggerProvider by lazy {
    symbols.doubleCheckCompanionObject.requireSimpleFunction("lazyFromDaggerProvider")
  }

  private val lazyFromJavaxProvider by lazy {
    symbols.doubleCheckCompanionObject.requireSimpleFunction("lazyFromJavaxProvider")
  }

  private val lazyFromJakartaProvider by lazy {
    symbols.doubleCheckCompanionObject.requireSimpleFunction("lazyFromJakartaProvider")
  }

  private val lazyFromMetroProvider by lazy {
    symbols.doubleCheckCompanionObject.requireSimpleFunction("lazyFromMetroProvider")
  }

  override fun isApplicable(classId: ClassId): Boolean {
    return classId in symbols.primitives
  }

  context(_: IrMetroContext, _: IrBuilderWithScope)
  override fun IrExpression.handleSameFramework(targetKey: IrContextualTypeKey): IrExpression {
    val provider = this@handleSameFramework
    val sourceClassId = provider.type.classOrNull?.owner?.classId
    val targetClassId = targetKey.rawType?.classOrNull?.owner?.classId

    // Same type, no conversion
    if (sourceClassId == targetClassId) {
      return provider
    }

    // Provider -> Lazy within Dagger framework
    if (targetClassId == DaggerSymbols.ClassIds.DAGGER_LAZY_CLASS_ID) {
      return provider.toLazy(targetKey)
    }

    // Should not reach here for Dagger-only types
    reportCompilerBug(
      "Unexpected conversion within Dagger framework: $sourceClassId -> $targetClassId"
    )
  }

  context(_: IrMetroContext, scope: IrBuilderWithScope)
  override fun fromMetroProvider(
    provider: IrExpression,
    targetKey: IrContextualTypeKey,
  ): IrExpression =
    with(scope) {
      val targetClass = targetKey.rawType?.classOrNull?.owner
      val targetClassId =
        targetClass?.classId ?: reportCompilerBug("Unexpected non-dagger provider type $targetKey")

      // Handle Dagger's Lazy type specially
      if (targetClassId == DaggerSymbols.ClassIds.DAGGER_LAZY_CLASS_ID) {
        return provider.toLazy(targetKey)
      }

      // Convert to dagger.internal.Provider
      if (targetClassId == DaggerSymbols.ClassIds.DAGGER_INTERNAL_PROVIDER_CLASS_ID) {
        return irInvoke(
          extensionReceiver = provider,
          callee = symbols.asDaggerInternalProvider,
          typeArgs = listOf(targetKey.typeKey.type),
        )
      }

      // Delegate to base class for javax/jakarta
      return super.fromMetroProvider(provider, targetKey)
    }

  context(context: IrMetroContext, scope: IrBuilderWithScope)
  override fun IrExpression.toMetroProvider(providerType: IrType): IrExpression =
    with(scope) {
      val provider = this@toMetroProvider
      // Extract the value type from the provider type
      val valueType =
        providerType.requireSimpleType().arguments[0].typeOrNull
          ?: reportCompilerBug(
            "Provider type missing type argument: ${providerType.dumpKotlinLike()}"
          )

      val implementsJakarta =
        provider.type.implements(JakartaSymbols.ClassIds.JAKARTA_PROVIDER_CLASS_ID)

      if (implementsJakarta) {
        return irInvoke(
          extensionReceiver = provider,
          callee = symbols.jakartaSymbols.asMetroProvider,
          typeArgs = listOf(valueType),
        )
      }

      // Delegate back up to the type converter, which'll choose the appropriate provider type
      // converter
      return with(context.metroSymbols.providerTypeConverter) {
        val type = valueType.wrapInProvider(context.metroSymbols.metroProvider)
        provider.convertTo(
          type.asContextualTypeKey(null, false, false, null),
          // This is only here if it's a dagger.internal.Provider.
          // Remap ("cast") as a jakarta provider and let it handle this
          providerType = valueType.wrapInProvider(symbols.jakartaSymbols.jakartaProvider),
        )
      }
    }

  context(_: IrMetroContext, scope: IrBuilderWithScope)
  override fun IrExpression.toLazy(targetKey: IrContextualTypeKey): IrExpression =
    with(scope) {
      val provider = this@toLazy

      // Determine which lazy function to use based on the provider type
      val lazyFunction =
        provider.type.rawTypeOrNull()?.let { rawType ->
          rawType
            .allSupertypesSequence(excludeSelf = false, excludeAny = true)
            .firstNotNullOfOrNull { type ->
              when (type.classOrNull?.owner?.classId) {
                DaggerSymbols.ClassIds.DAGGER_INTERNAL_PROVIDER_CLASS_ID -> lazyFromDaggerProvider
                JavaxSymbols.ClassIds.JAVAX_PROVIDER_CLASS_ID -> lazyFromJavaxProvider
                JakartaSymbols.ClassIds.JAKARTA_PROVIDER_CLASS_ID -> lazyFromJakartaProvider
                Symbols.ClassIds.metroProvider -> lazyFromMetroProvider
                else -> null
              }
            }
        } ?: reportCompilerBug("Unexpected provider type: ${provider.type.dumpKotlinLike()}")

      return irInvoke(
        dispatchReceiver = irGetObject(symbols.doubleCheckCompanionObject),
        callee = lazyFunction,
        args = listOf(provider),
        typeHint = targetKey.toIrType(),
        typeArgs = listOf(provider.type, targetKey.typeKey.type),
      )
    }
}
