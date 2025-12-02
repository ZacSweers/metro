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
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.plugin.SimpleFunctionBuildingContext
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.ir.builders.Scope
import org.jetbrains.kotlin.ir.builders.declarations.IrFieldBuilder
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

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
  @CompatApi(since = "2.3.0", reason = CompatApi.Reason.DELETED)
  public fun FirBasedSymbol<*>.getContainingClassSymbol(): FirClassLikeSymbol<*>?

  /**
   * Returns the containing class or file if the callable is top-level. The containing symbol is
   * resolved using the declaration-site session.
   */
  @CompatApi(since = "2.3.0", reason = CompatApi.Reason.DELETED)
  public fun FirCallableSymbol<*>.getContainingSymbol(session: FirSession): FirBasedSymbol<*>?

  /** The containing symbol is resolved using the declaration-site session. */
  @CompatApi(since = "2.3.0", reason = CompatApi.Reason.DELETED)
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
  @CompatApi(
    since = "2.3.0",
    reason = CompatApi.Reason.ABI_CHANGE,
    message = "containingFileName parameter was added",
  )
  @ExperimentalTopLevelDeclarationsGenerationApi
  public fun FirExtension.createTopLevelFunction(
    key: GeneratedDeclarationKey,
    callableId: CallableId,
    returnType: ConeKotlinType,
    containingFileName: String? = null,
    config: SimpleFunctionBuildingContext.() -> Unit = {},
  ): FirFunction

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
  @CompatApi(
    since = "2.3.0",
    reason = CompatApi.Reason.ABI_CHANGE,
    message = "containingFileName parameter was added",
  )
  @ExperimentalTopLevelDeclarationsGenerationApi
  public fun FirExtension.createTopLevelFunction(
    key: GeneratedDeclarationKey,
    callableId: CallableId,
    returnTypeProvider: (List<FirTypeParameter>) -> ConeKotlinType,
    containingFileName: String? = null,
    config: SimpleFunctionBuildingContext.() -> Unit = {},
  ): FirFunction

  /**
   * Creates a member function for [owner] with specified [returnType].
   *
   * Return type is [FirFunction] instead of `FirSimpleFunction` because it was renamed to
   * `FirNamedFunction` in Kotlin 2.3.20.
   */
  @CompatApi(
    since = "2.3.20",
    reason = CompatApi.Reason.ABI_CHANGE,
    message = "FirSimpleFunction was renamed to FirNamedFunction",
  )
  public fun FirExtension.createMemberFunction(
    owner: FirClassSymbol<*>,
    key: GeneratedDeclarationKey,
    name: Name,
    returnType: ConeKotlinType,
    config: SimpleFunctionBuildingContext.() -> Unit = {},
  ): FirFunction

  /**
   * Creates a member function for [owner] with return type provided by [returnTypeProvider].
   *
   * Return type is [FirFunction] instead of `FirSimpleFunction` because it was renamed to
   * `FirNamedFunction` in Kotlin 2.3.20.
   */
  @CompatApi(
    since = "2.3.20",
    reason = CompatApi.Reason.ABI_CHANGE,
    message = "FirSimpleFunction was renamed to FirNamedFunction",
  )
  public fun FirExtension.createMemberFunction(
    owner: FirClassSymbol<*>,
    key: GeneratedDeclarationKey,
    name: Name,
    returnTypeProvider: (List<FirTypeParameter>) -> ConeKotlinType,
    config: SimpleFunctionBuildingContext.() -> Unit = {},
  ): FirFunction

  // Changed to a new KtSourceElementOffsetStrategy overload in Kotlin 2.3.0
  public fun KtSourceElement.fakeElement(
    newKind: KtFakeSourceElementKind,
    startOffset: Int = -1,
    endOffset: Int = -1,
  ): KtSourceElement

  @CompatApi(
    since = "2.3.0",
    reason = CompatApi.Reason.ABI_CHANGE,
    message = "changed hasMustUseReturnValue to returnValueStatus",
  )
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
  @CompatApi(since = "2.3.0", reason = CompatApi.Reason.ABI_CHANGE)
  public fun IrClass.addFakeOverrides(typeSystem: IrTypeSystemContext)

  @CompatApi(
    since = "2.3.0",
    reason = CompatApi.Reason.ABI_CHANGE,
    message = "added inventUniqueName param",
  )
  public fun Scope.createTemporaryVariableDeclarationCompat(
    irType: IrType,
    nameHint: String? = null,
    isMutable: Boolean = false,
    origin: IrDeclarationOrigin = IrDeclarationOrigin.IR_TEMPORARY_VARIABLE,
    startOffset: Int,
    endOffset: Int,
  ): IrVariable

  @CompatApi(
    since = "2.3.20",
    reason = CompatApi.Reason.COMPAT,
    message =
      "We use FirFunction instead of FirSimpleFunction or FirNamedFunction to better interop and occasionally need to check for certain that this is a named function",
  )
  public fun FirFunction.isNamedFunction(): Boolean

  /**
   * Builds a member function using the version-appropriate builder.
   *
   * This abstraction exists because `FirSimpleFunctionBuilder` was renamed to
   * `FirNamedFunctionBuilder` in Kotlin 2.3.20, causing linkage failures at runtime.
   *
   * @param owner The class that will contain this function
   * @param returnTypeProvider Provider for the return type, called after type parameters are added
   * @param callableId The callable ID for the function
   * @param origin The declaration origin
   * @param visibility The visibility of the function
   * @param modality The modality of the function
   * @param body Configuration block for type parameters and value parameters
   */
  @CompatApi(
    since = "2.3.20",
    reason = CompatApi.Reason.RENAMED,
    message = "FirSimpleFunctionBuilder was renamed to FirNamedFunctionBuilder",
  )
  public fun FirDeclarationGenerationExtension.buildMemberFunction(
    owner: FirClassLikeSymbol<*>,
    returnTypeProvider: (List<FirTypeParameterRef>) -> ConeKotlinType,
    callableId: CallableId,
    origin: FirDeclarationOrigin,
    visibility: Visibility,
    modality: Modality,
    body: FunctionBuilderScope.() -> Unit,
  ): FirFunction

  /**
   * A stable interface for configuring function builders across Kotlin compiler versions.
   *
   * This abstraction exists because `FirSimpleFunctionBuilder` was renamed to
   * `FirNamedFunctionBuilder` in Kotlin 2.3.20, causing linkage failures at runtime.
   */
  public interface FunctionBuilderScope {
    public val symbol: FirNamedFunctionSymbol
    public val typeParameters: MutableList<FirTypeParameter>
    public val valueParameters: MutableList<FirValueParameter>
  }

  @CompatApi(
    since = "2.3.20",
    reason = CompatApi.Reason.ABI_CHANGE,
    message =
      "usages of IrDeclarationOrigin constants are getting inlined and causing runtime failures, so we have a non-inline version to defeat this inlining",
  )
  public fun IrProperty.addBackingFieldCompat(builder: IrFieldBuilder.() -> Unit = {}): IrField
}

private data class FactoryData(val version: String, val factory: CompatContext.Factory)

internal annotation class CompatApi(
  val since: String,
  val reason: Reason,
  val message: String = "",
) {
  enum class Reason {
    DELETED,
    RENAMED,
    ABI_CHANGE,
    COMPAT,
  }
}
