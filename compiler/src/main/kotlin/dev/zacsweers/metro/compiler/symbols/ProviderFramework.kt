// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.symbols

import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.getAllSuperTypes
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
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
  fun IrExpression.fromMetroProvider(targetKey: IrContextualTypeKey): IrExpression

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
  override fun IrExpression.fromMetroProvider(targetKey: IrContextualTypeKey): IrExpression {
    val provider = this@fromMetroProvider
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

/**
 * Dagger's provider framework - handles Dagger, javax, and jakarta providers/lazy.
 *
 * Supports:
 * - dagger.Lazy
 * - dagger.internal.Provider
 * - javax.inject.Provider
 * - jakarta.inject.Provider
 */
internal class DaggerProviderFramework(private val symbols: DaggerSymbols) : ProviderFramework {

  // Lazy creation functions
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

    // Different provider types within Dagger - convert through Metro
    val metroProvider = provider.toMetroProvider(provider.type)
    return metroProvider.fromMetroProvider(targetKey)
  }

  context(_: IrMetroContext, scope: IrBuilderWithScope)
  override fun IrExpression.fromMetroProvider(targetKey: IrContextualTypeKey): IrExpression =
    with(scope) {
      val provider = this@fromMetroProvider
      val targetClass = targetKey.rawType?.classOrNull?.owner
      val targetClassId =
        targetClass?.classId
          ?: reportCompilerBug("Unexpected non-jakarta/javax provider type $targetKey")

      // Handle Dagger's Lazy type specially
      if (targetClassId == DaggerSymbols.ClassIds.DAGGER_LAZY_CLASS_ID) {
        return provider.toLazy(targetKey)
      }

      // Convert to the appropriate provider type
      val conversionFunction =
        when (targetClassId) {
          DaggerSymbols.ClassIds.DAGGER_INTERNAL_PROVIDER_CLASS_ID ->
            symbols.asDaggerInternalProvider
          DaggerSymbols.ClassIds.JAVAX_PROVIDER_CLASS_ID -> symbols.asJavaxProvider
          DaggerSymbols.ClassIds.JAKARTA_PROVIDER_CLASS_ID -> symbols.asJakartaProvider
          else -> reportCompilerBug("Unexpected non-dagger/jakarta/javax provider $targetClassId")
        }

      return irInvoke(
        extensionReceiver = provider,
        callee = conversionFunction,
        typeArgs = listOf(targetKey.typeKey.type),
      )
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
      // Determine which lazy function to use based on the provider type
      val lazyFunction =
        provider.type.rawTypeOrNull()?.let { rawType ->
          rawType.getAllSuperTypes(excludeSelf = false, excludeAny = true).firstNotNullOfOrNull {
            type ->
            when (type.classOrNull?.owner?.classId) {
              DaggerSymbols.ClassIds.DAGGER_INTERNAL_PROVIDER_CLASS_ID -> lazyFromDaggerProvider
              DaggerSymbols.ClassIds.JAVAX_PROVIDER_CLASS_ID -> lazyFromJavaxProvider
              DaggerSymbols.ClassIds.JAKARTA_PROVIDER_CLASS_ID -> lazyFromJakartaProvider
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
