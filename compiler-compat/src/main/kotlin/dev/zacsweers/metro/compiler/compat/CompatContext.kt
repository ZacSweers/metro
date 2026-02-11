// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat

import java.util.ServiceLoader
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithoutSource
import org.jetbrains.kotlin.diagnostics.KtSourcelessDiagnosticFactory
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
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
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
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
    private fun loadFactories(): Sequence<Factory> {
      return ServiceLoader.load(Factory::class.java, Factory::class.java.classLoader).asSequence()
    }

    /**
     * Load [factories][Factory] and pick the highest compatible version (by [Factory.minVersion]).
     *
     * `dev` track versions are special-cased to avoid issues with divergent release tracks.
     *
     * When the current version is a dev build:
     * 1. First, look for dev track factories and compare only within the dev track
     * 2. If no dev factory matches, fall back to non-dev factories
     *
     * This ensures that a dev build like 2.3.20-dev-7791 doesn't incorrectly match a 2.3.20-Beta1
     * factory just because beta > dev in maturity ordering.
     */
    internal fun resolveFactory(
      knownVersion: KotlinToolingVersion? = null,
      factories: Sequence<Factory> = loadFactories(),
    ): Factory {
      // TODO short-circuit if we hit a factory with the exact version
      val factoryDataList =
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
          .toList()

      val currentVersion =
        knownVersion ?: factoryDataList.firstOrNull()?.version ?: error("No factories available")

      val targetFactory = resolveFactoryForVersion(currentVersion, factoryDataList)
      return targetFactory
        ?: error(
          """
            Unrecognized Kotlin version!

            Available factories for: ${factories.joinToString(separator = "\n") { it.minVersion }}
            Detected version(s): ${factories.map { it.currentVersion }.distinct().joinToString(separator = "\n")}
          """
            .trimIndent()
        )
    }

    private fun resolveFactoryForVersion(
      currentVersion: KotlinToolingVersion,
      factoryDataList: List<FactoryData>,
    ): Factory? {
      // If current version is DEV, try DEV track factories first
      if (currentVersion.isDev) {
        val devFactories =
          factoryDataList.filter { KotlinToolingVersion(it.factory.minVersion).isDev }
        val devMatch = findHighestCompatibleFactory(currentVersion, devFactories)
        if (devMatch != null) {
          return devMatch
        }

        // Fall back to non-DEV factories.
        // Use the base version (strip dev classifier) for comparison, because
        // 2.2.20-dev-5812 is a dev build OF 2.2.20 and should match the 2.2.20 factory,
        // but KotlinToolingVersion ordering puts DEV < STABLE so the comparison would
        // otherwise exclude it.
        val nonDevFactories =
          factoryDataList.filter { !KotlinToolingVersion(it.factory.minVersion).isDev }
        val baseVersion =
          KotlinToolingVersion(
            currentVersion.major,
            currentVersion.minor,
            currentVersion.patch,
            null,
          )
        return findHighestCompatibleFactory(baseVersion, nonDevFactories)
      }

      // For non-DEV versions, only consider non-DEV factories
      val nonDevFactories =
        factoryDataList.filter { !KotlinToolingVersion(it.factory.minVersion).isDev }
      return findHighestCompatibleFactory(currentVersion, nonDevFactories)
    }

    private fun findHighestCompatibleFactory(
      currentVersion: KotlinToolingVersion,
      factoryDataList: List<FactoryData>,
    ): Factory? {
      return factoryDataList
        .filter { (_, factory) -> currentVersion >= KotlinToolingVersion(factory.minVersion) }
        .maxByOrNull { (_, factory) -> KotlinToolingVersion(factory.minVersion) }
        ?.factory
    }

    public fun create(knownVersion: KotlinToolingVersion? = null): CompatContext =
      resolveFactory(knownVersion).create()
  }

  public interface Factory {
    public val minVersion: String

    /** Attempts to get the current compiler version or throws and exception if it cannot. */
    public val currentVersion: String
      get() = loadCompilerVersionString()

    public fun create(): CompatContext

    public companion object Companion {
      private const val COMPILER_VERSION_FILE = "META-INF/compiler.version"

      public fun loadCompilerVersion(): KotlinToolingVersion {
        return KotlinToolingVersion(loadCompilerVersionString())
      }

      public fun loadCompilerVersionOrNull(): KotlinToolingVersion? {
        return loadCompilerVersionStringOrNull()?.let(::KotlinToolingVersion)
      }

      public fun loadCompilerVersionString(): String {
        return loadCompilerVersionStringOrNull()
          ?: throw AssertionError(
            "'$COMPILER_VERSION_FILE' not found in the classpath or was blank"
          )
      }

      public fun loadCompilerVersionStringOrNull(): String? {
        val inputStream =
          FirExtensionRegistrar::class.java.classLoader?.getResourceAsStream(COMPILER_VERSION_FILE)
            ?: return null
        return inputStream.bufferedReader().use { it.readText() }.takeUnless { it.isBlank() }
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
  @IgnorableReturnValue
  public fun IrProperty.addBackingFieldCompat(builder: IrFieldBuilder.() -> Unit = {}): IrField

  @CompatApi(
    since = "2.3.20",
    reason = CompatApi.Reason.COMPAT,
    message =
      """
        IR doesn't support reporting source-less elements until 2.3.20.
        Note that this is somewhat fluid across the 2.3.20 dev builds
      """,
  )
  public val supportsSourcelessIrDiagnostics: Boolean
    get() = false

  @CompatApi(
    since = "2.3.20-dev-7621",
    reason = CompatApi.Reason.COMPAT,
    message =
      """
        Compat backport for the new sourceless CompilerMessageSourceLocation
        https://github.com/JetBrains/kotlin/commit/5ba8a58457f2e6b4f8a943d0c17104cda6cd4484
      """,
  )
  public fun KtSourcelessDiagnosticFactory.createCompat(
    message: String,
    location: CompilerMessageSourceLocation?,
    languageVersionSettings: LanguageVersionSettings,
  ): KtDiagnosticWithoutSource? {
    return create(message, languageVersionSettings)
  }

  @CompatApi(
    since = "2.3.20-dev-7621",
    reason = CompatApi.Reason.COMPAT,
    message =
      """
        Compat backport for the new sourceless reporting
        https://github.com/JetBrains/kotlin/commit/5ba8a58457f2e6b4f8a943d0c17104cda6cd4484
      """,
  )
  public fun IrDiagnosticReporter.reportCompat(
    factory: KtSourcelessDiagnosticFactory,
    message: String,
  ) {
    throw NotImplementedError("reportCompat is not implemented on this version of the compiler")
  }

  @CompatApi(
    since = "2.3.0",
    reason = CompatApi.Reason.COMPAT,
    message = "2.3 moved APIs around here",
  )
  public val FirClassLikeSymbol<*>.isLocalCompat: Boolean

  @CompatApi(
    since = "2.3.0",
    reason = CompatApi.Reason.COMPAT,
    message = "2.3 moved APIs around here",
  )
  public val FirClass.isLocalCompat: Boolean

  @CompatApi(
    since = "2.4.0",
    reason = CompatApi.Reason.ABI_CHANGE,
    message = "2.4 moved APIs around here",
  )
  context(_: CompilerPluginRegistrar)
  public fun CompilerPluginRegistrar.ExtensionStorage.registerFirExtensionCompat(
    extension: FirExtensionRegistrar
  )

  @CompatApi(
    since = "2.4.0",
    reason = CompatApi.Reason.ABI_CHANGE,
    message = "2.4 moved APIs around here",
  )
  context(_: CompilerPluginRegistrar)
  public fun CompilerPluginRegistrar.ExtensionStorage.registerIrExtensionCompat(
    extension: IrGenerationExtension
  )
}

private data class FactoryData(
  val version: KotlinToolingVersion,
  val factory: CompatContext.Factory,
) {
  companion object {
    operator fun invoke(version: String, factory: CompatContext.Factory): FactoryData =
      FactoryData(KotlinToolingVersion(version), factory)
  }
}

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
