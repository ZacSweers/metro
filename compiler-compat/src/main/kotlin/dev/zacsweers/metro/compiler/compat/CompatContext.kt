// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat

import java.io.FileNotFoundException
import java.util.ServiceLoader
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.plugin.SimpleFunctionBuildingContext
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.ir.builders.Scope
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.name.CallableId

public interface CompatContext {
  public companion object Companion {
    private val _instance: CompatContext by lazy { create() }

    // TODO ehhhh
    public fun getInstance(): CompatContext = _instance

    private fun loadFactories(): Sequence<Factory> {
      return ServiceLoader.load(Factory::class.java, Factory::class.java.classLoader).asSequence()
    }

    /**
     * Load [factories][Factory] and pick the highest compatible version (by [Factory.minVersion])
     */
    private fun resolveFactory(
      factories: Sequence<Factory> = loadFactories(),
      testVersion: String? = null,
    ): Factory {
      val targetFactory =
        factories
          .mapNotNull { factory ->
            // Filter out any factories that can't compute the Kotlin version, as
            // they're _definitely_ not compatible
            try {
              FactoryData(factory.currentVersion, factory)
            } catch (_: Throwable) {
              null
            }
          }
          .filter { (version, factory) -> (testVersion ?: version) >= factory.minVersion }
          .maxByOrNull { (_, factory) -> factory.minVersion }
          ?.factory
          ?: error(
            """
              Unrecognized Kotlin version!

              Available factories for: ${factories.joinToString(separator = "\n") { it.minVersion }}
              Detected version(s): ${factories.map { it.currentVersion }.distinct().joinToString(separator = "\n")}
            """
              .trimIndent()
          )
      return targetFactory
    }

    private fun create(): CompatContext = resolveFactory().create()
  }

  public interface Factory {
    public val minVersion: String

    /** Attempts to get the current compiler version or throws and exception if it cannot. */
    public val currentVersion: String
      get() = loadCompilerVersion()

    public fun create(): CompatContext

    public companion object Companion {
      private const val COMPILER_VERSION_FILE = "META-INF/compiler.version"

      internal fun loadCompilerVersion(): String {
        val inputStream =
          FirExtensionRegistrar::class.java.classLoader!!.getResourceAsStream(COMPILER_VERSION_FILE)
            ?: throw FileNotFoundException("'$COMPILER_VERSION_FILE' not found in the classpath")
        return inputStream.bufferedReader().use { it.readText() }
      }
    }
  }

  /**
   * Returns the ClassLikeDeclaration where the Fir object has been defined or null if no proper
   * declaration has been found. The containing symbol is resolved using the declaration-site
   * session. For example:
   * ```kotlin
   * expect class MyClass {
   *     fun test() // (1)
   * }
   *
   * actual class MyClass {
   *     actual fun test() {} // (2)
   * }
   * ```
   *
   * Calling [getContainingClassSymbol] for the symbol of `(1)` will return `expect class MyClass`,
   * but calling it for `(2)` will give `actual class MyClass`.
   */
  // Deleted in Kotlin 2.3.0
  public fun FirBasedSymbol<*>.getContainingClassSymbol(): FirClassLikeSymbol<*>?

  /**
   * Returns the containing class or file if the callable is top-level. The containing symbol is
   * resolved using the declaration-site session.
   */
  // Deleted in Kotlin 2.3.0
  public fun FirCallableSymbol<*>.getContainingSymbol(session: FirSession): FirBasedSymbol<*>?

  /** The containing symbol is resolved using the declaration-site session. */
  // Deleted in Kotlin 2.3.0
  public fun FirDeclaration.getContainingClassSymbol(): FirClassLikeSymbol<*>?

  /**
   * Creates a top-level function with [callableId] and specified [returnType].
   *
   * Type and value parameters can be configured with [config] builder.
   *
   * @param containingFileName defines the name for a newly created file with this property. The
   *   full file path would be `package/of/the/property/containingFileName.kt. If null is passed,
   *   then `__GENERATED BUILTINS DECLARATIONS__.kt` would be used
   */
  // Kotlin 2.3.0 added containingFileName
  @ExperimentalTopLevelDeclarationsGenerationApi
  public fun FirExtension.createTopLevelFunction(
    key: GeneratedDeclarationKey,
    callableId: CallableId,
    returnType: ConeKotlinType,
    containingFileName: String? = null,
    config: SimpleFunctionBuildingContext.() -> Unit = {},
  ): FirSimpleFunction

  /**
   * Creates a top-level function with [callableId] and return type provided by
   * [returnTypeProvider]. Use this overload when return type references type parameters of the
   * created function.
   *
   * Type and value parameters can be configured with [config] builder.
   *
   * @param containingFileName defines the name for a newly created file with this property. The
   *   full file path would be `package/of/the/property/containingFileName.kt. If null is passed,
   *   then `__GENERATED BUILTINS DECLARATIONS__.kt` would be used
   */
  // Kotlin 2.3.0 added containingFileName
  @ExperimentalTopLevelDeclarationsGenerationApi
  public fun FirExtension.createTopLevelFunction(
    key: GeneratedDeclarationKey,
    callableId: CallableId,
    returnTypeProvider: (List<FirTypeParameter>) -> ConeKotlinType,
    containingFileName: String? = null,
    config: SimpleFunctionBuildingContext.() -> Unit = {},
  ): FirSimpleFunction

  // Changed to a new KtSourceElementOffsetStrategy overload in Kotlin 2.3.0
  public fun KtSourceElement.fakeElement(
    newKind: KtFakeSourceElementKind,
    startOffset: Int = -1,
    endOffset: Int = -1,
  ): KtSourceElement

  // Kotlin 2.3.0 changed hasMustUseReturnValue to returnValueStatus
  public fun FirDeclarationStatus.copy(
    visibility: Visibility? = this.visibility,
    modality: Modality? = this.modality,
    isExpect: Boolean = this.isExpect,
    isActual: Boolean = this.isActual,
    isOverride: Boolean = this.isOverride,
    isOperator: Boolean = this.isOperator,
    isInfix: Boolean = this.isInfix,
    isInline: Boolean = this.isInline,
    isValue: Boolean = this.isValue,
    isTailRec: Boolean = this.isTailRec,
    isExternal: Boolean = this.isExternal,
    isConst: Boolean = this.isConst,
    isLateInit: Boolean = this.isLateInit,
    isInner: Boolean = this.isInner,
    isCompanion: Boolean = this.isCompanion,
    isData: Boolean = this.isData,
    isSuspend: Boolean = this.isSuspend,
    isStatic: Boolean = this.isStatic,
    isFromSealedClass: Boolean = this.isFromSealedClass,
    isFromEnumClass: Boolean = this.isFromEnumClass,
    isFun: Boolean = this.isFun,
    hasStableParameterNames: Boolean = this.hasStableParameterNames,
  ): FirDeclarationStatus

  // Parameters changed in Kotlin 2.3.0
  public fun IrClass.addFakeOverrides(typeSystem: IrTypeSystemContext)

  // Kotlin 2.3.0 added inventUniqueName param
  public fun Scope.createTemporaryVariableDeclarationCompat(
    irType: IrType,
    nameHint: String? = null,
    isMutable: Boolean = false,
    origin: IrDeclarationOrigin = IrDeclarationOrigin.IR_TEMPORARY_VARIABLE,
    startOffset: Int,
    endOffset: Int,
  ): IrVariable
}

private data class FactoryData(val version: String, val factory: CompatContext.Factory)
