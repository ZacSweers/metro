// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.CompanionMode
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.remapTypes
import dev.zacsweers.metro.compiler.ir.transformers.factoryHelperDeclarationContainer
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.proto.SignatureCarrier
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.runIf
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.parentAsClass
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
  open val inlinedValue: IrInlinedProvider? = null

  /**
   * Owns the binding for this factory's parameter types within its compilation. Remapped factories
   * get separate values, and memoize publishes completed bindings to parallel graph builders.
   */
  val providedBinding: IrBinding.Provided by memoize {
    IrBinding.Provided(
      providerFactory = this,
      contextualTypeKey = IrContextualTypeKey(typeKey),
      parameters = parameters,
      annotations = annotations,
    )
  }

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

  /**
   * Returns the type arguments for a direct call to [realFunction], the source provider function.
   * It's null for a provider without its own type parameters. A generic provider must be an applied
   * view here because codegen can't infer its type arguments.
   */
  fun directCallTypeArguments(realFunction: IrFunction): List<IrType>? {
    if (this !is Metro) {
      return null
    }
    if (isGeneric) {
      reportCompilerBug("Generic provider $callableId reached codegen without type arguments")
    }
    val arguments = functionTypeArguments ?: return null
    if (realFunction.typeParameters.size != arguments.size) {
      reportCompilerBug(
        "Provider $callableId declares ${realFunction.typeParameters.size} type parameters " +
          "but ${arguments.size} type arguments were inferred",
      )
    }
    return arguments
  }

  class Metro(
    override val factoryClass: IrClass,
    override val typeKey: IrTypeKey,
    override val rawTypeKey: IrTypeKey,
    override val contextualTypeKey: IrContextualTypeKey,
    override val realDeclaration: IrDeclaration?,
    private val callableMetadata: IrCallableMetadata,
    val signatureCarrier: SignatureCarrier,
    /** The producer's mode, which can differ from this compilation's mode. */
    private val companionMode: CompanionMode,
    parametersLazy: Lazy<Parameters>,
    override val inlinedValue: IrInlinedProvider? = null,
    override val creatorTypeArguments: List<IrType>? = null,
    /**
     * The types [withFunctionTypeArguments] substituted for [functionTypeParameters], in the same
     * order. It's null for a factory that isn't an applied view.
     */
    val functionTypeArguments: List<IrType>? = null,
    /**
     * Substitutes the container's type parameters as [withRemappedTypes] did. It covers every copy
     * of them, so it also applies to the bounds of [functionTypeParameters]. It's null when no
     * container substitution happened.
     */
    val containerTypeRemapper: TypeRemapper? = null,
  ) : ProviderFactory() {
    override val helperOwner: IrClass by memoize {
      factoryClass.factoryHelperDeclarationContainer(companionMode)
    }

    val signatureFunction: IrSimpleFunction
      get() = callableMetadata.signatureFunction

    override val callableId: CallableId
      get() = callableMetadata.callableId

    override val function: IrSimpleFunction
      get() = callableMetadata.function

    override val annotations: MetroAnnotations<IrAnnotation>
      get() = callableMetadata.annotations

    override val isPropertyAccessor: Boolean
      get() = callableMetadata.isPropertyAccessor

    /** Whether the source provider is a companion-block member. [function] can be a helper. */
    val isCompanionBlockMember: Boolean
      get() = callableMetadata.isCompanionBlockMember

    override val newInstanceName: Name
      get() =
        callableMetadata.newInstanceName
          ?: reportCompilerBug(
            "No newInstanceName present in CallableMetadata for provider factory for $callableId",
          )

    override val parameters by parametersLazy

    override val isDaggerFactory: Boolean = false

    /**
     * The provider function's own type parameters. They're empty for ordinary providers.
     *
     * [typeKey], [rawTypeKey], [contextualTypeKey], and [parameters] mention exactly these symbols
     * for function-level type parameters. This holds for providers declared in this compilation and
     * for ones loaded from another module's signature carrier. Container substitution through
     * [withRemappedTypes] leaves them in place.
     *
     * An applied view from [withFunctionTypeArguments] still lists the declaration's type
     * parameters here. Its types no longer mention them, and [functionTypeArguments] holds what
     * replaced them.
     */
    val functionTypeParameters: List<IrTypeParameter>
      get() = callableMetadata.functionTypeParameters

    /**
     * True if the provider function declares its own type parameters and they haven't been applied
     * yet. A generic provider's key is a pattern. An applied view's key is concrete, so it's false
     * there.
     */
    val isGeneric: Boolean
      get() = functionTypeParameters.isNotEmpty() && functionTypeArguments == null

    /**
     * True if any of the provider function's own type parameters is `reified`. It stays true on an
     * applied view because [functionTypeParameters] still lists the declaration's type parameters.
     *
     * The factory class can't call a reified provider, so the graph always calls the function or
     * its `newInstance` helper directly. In this compilation [functionTypeParameters] are the
     * source function's own. From another module they're the signature carrier's copies. Those keep
     * `reified` because a reified provider is always inline, and inline carriers keep it.
     */
    val isReified: Boolean
      get() = functionTypeParameters.any { it.isReified }

    /**
     * Substitutes the container's type parameters through [remapper]. Function-level type
     * parameters stay unchanged, so a generic provider stays generic.
     */
    fun withRemappedTypes(remapper: TypeRemapper): Metro {
      if (functionTypeArguments != null) {
        reportCompilerBug(
          "Container substitution must happen before function type arguments are applied to " +
            "$callableId",
        )
      }
      val sourceClass = factoryClass.parentAsClass
      val concreteTypes = sourceClass.typeParameters.map { remapper.remapType(it.defaultType) }
      // Each declaration lists the container's type parameters (or copies of them) first. The
      // source function itself declares only its own type parameters.
      val functionTypeParameterCount = functionTypeParameters.size
      val containerTypeParameterLists =
        listOf(
          sourceClass.typeParameters,
          factoryClass.typeParameters,
          signatureFunction.typeParameters,
          function.typeParameters.dropLast(functionTypeParameterCount),
        )
      val factoryRemapper =
        typeRemapperFor(
          buildMap {
            for (typeParameters in containerTypeParameterLists) {
              typeParameters.zip(concreteTypes).forEach { (typeParameter, concreteType) ->
                put(typeParameter.symbol, concreteType)
              }
            }
          },
        )
      return Metro(
        factoryClass = factoryClass,
        typeKey = typeKey.remapTypes(factoryRemapper),
        rawTypeKey = rawTypeKey.remapTypes(factoryRemapper),
        contextualTypeKey = contextualTypeKey.remapType(factoryRemapper),
        realDeclaration = realDeclaration,
        callableMetadata = callableMetadata,
        signatureCarrier = signatureCarrier,
        companionMode = companionMode,
        parametersLazy = lazy { parameters.remapTypes(factoryRemapper) },
        inlinedValue = inlinedValue,
        creatorTypeArguments = concreteTypes,
        containerTypeRemapper = factoryRemapper,
      )
    }

    /**
     * Returns an applied view of this generic provider with [arguments] substituted for
     * [functionTypeParameters], in the same order. The view's keys and parameters are concrete.
     *
     * [creatorTypeArguments] become the container's arguments followed by [arguments]. That's the
     * order the generated `create()` and `newInstance` helpers declare their type parameters in. A
     * container with type parameters must already have them substituted by [withRemappedTypes].
     *
     * The view has no inlined value because the source body can mention the type parameters.
     */
    fun withFunctionTypeArguments(arguments: List<IrType>): Metro {
      if (!isGeneric) {
        reportCompilerBug("Can't apply function type arguments to non-generic provider $callableId")
      }
      if (arguments.size != functionTypeParameters.size) {
        reportCompilerBug(
          "Provider $callableId declares ${functionTypeParameters.size} type parameters but got " +
            "${arguments.size} arguments",
        )
      }
      val containerTypeParameterCount = factoryClass.parentAsClass.typeParameters.size
      val containerArguments = creatorTypeArguments.orEmpty()
      if (containerArguments.size != containerTypeParameterCount) {
        reportCompilerBug(
          "Provider $callableId is declared in a container with $containerTypeParameterCount " +
            "type parameters, but ${containerArguments.size} container type arguments were " +
            "applied. Container type parameters must be substituted before function type " +
            "arguments.",
        )
      }
      val functionRemapper =
        typeRemapperFor(
          functionTypeParameters.zip(arguments).associate { (typeParameter, argument) ->
            typeParameter.symbol to argument
          },
        )
      return Metro(
        factoryClass = factoryClass,
        typeKey = typeKey.remapTypes(functionRemapper),
        rawTypeKey = rawTypeKey.remapTypes(functionRemapper),
        contextualTypeKey = contextualTypeKey.remapType(functionRemapper),
        realDeclaration = realDeclaration,
        callableMetadata = callableMetadata,
        signatureCarrier = signatureCarrier,
        companionMode = companionMode,
        parametersLazy = lazy { parameters.remapTypes(functionRemapper) },
        inlinedValue = null,
        creatorTypeArguments = containerArguments + arguments,
        functionTypeArguments = arguments,
        containerTypeRemapper = containerTypeRemapper,
      )
    }
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

    // Dagger and Anvil record no helper owner, so this reads the factory's shape.
    override val helperOwner: IrClass by memoize {
      factoryClass.requireStaticIshDeclarationContainer()
    }

    fun withRemappedTypes(remapper: TypeRemapper): Dagger {
      return Dagger(
        factoryClass = factoryClass,
        typeKey = typeKey.remapTypes(remapper),
        contextualTypeKey = contextualTypeKey.remapType(remapper),
        rawTypeKey = rawTypeKey.remapTypes(remapper),
        callableId = callableId,
        annotations = annotations,
        parameters = parameters.remapTypes(remapper),
        function = function,
        isPropertyAccessor = isPropertyAccessor,
        newInstanceName = newInstanceName,
        realDeclaration = realDeclaration,
      )
    }
  }

  companion object {
    context(context: IrMetroContext)
    operator fun invoke(
      contextKey: IrContextualTypeKey,
      clazz: IrClass,
      signatureFunction: IrSimpleFunction,
      signatureCarrier: SignatureCarrier,
      /** The producer's mode, read from Metro metadata for external factories. */
      companionMode: CompanionMode,
      sourceAnnotations: MetroAnnotations<IrAnnotation>?,
      callableMetadata: IrCallableMetadata,
      /** Pre-computed real declaration for in-compilation case. If null, will be looked up. */
      realDeclaration: IrDeclaration? = null,
      inlinedValue: IrInlinedProvider? = null,
      computeInlinedValue: Boolean = true,
    ): Metro? {
      // Keys read from the signature carrier mention its copies of the function's type parameters.
      val functionKeyRemapper =
        signatureFunctionTypeParameterRemapper(clazz, signatureFunction, callableMetadata)
      val signatureContextKey =
        if (functionKeyRemapper === NOOP_TYPE_REMAPPER) {
          contextKey
        } else {
          contextKey.remapType(functionKeyRemapper)
        }
      val rawTypeKey =
        signatureContextKey.typeKey.copy(qualifier = callableMetadata.annotations.qualifier)
      val typeKey = rawTypeKey.transformIfIntoMultibinding(callableMetadata.annotations)
      val hasInstanceDependency =
        callableMetadata.function.dispatchReceiverParameter != null &&
          callableMetadata.function.parentClassOrNull?.isObject != true

      // Validate and optionally patch parameter types due to
      // https://github.com/ZacSweers/metro/issues/1556
      val hadUnpatchedMismatch =
        checkSignatureCarrierParamMismatches(
          factoryClass = clazz,
          helperOwner = clazz.factoryHelperDeclarationContainer(companionMode),
          newInstanceFunctionName = callableMetadata.newInstanceName!!.asString(),
          signatureFunction = signatureFunction,
          signatureParams = {
            if (signatureCarrier == SignatureCarrier.MIRROR_FUNCTION) {
              signatureFunction.parameters().nonDispatchParameters
            } else {
              callableMetadata.function.parameters().nonDispatchParameters
            }
          },
          reportingFunction = callableMetadata.function,
          signatureTypeParameters = {
            if (signatureCarrier == SignatureCarrier.MIRROR_FUNCTION) {
              signatureFunction.typeParameters
            } else {
              callableMetadata.function.typeParameters
            }
          },
          primaryConstructorParamOffset =
            if (hasInstanceDependency) {
              1
            } else {
              0
            },
        ) {
          it
            .parameters()
            .regularParameters
            // Instance providers carry their owner as the first helper argument.
            .runIf(hasInstanceDependency) { drop(1) }
        }

      if (hadUnpatchedMismatch) {
        return null
      }

      val transformedContextKey = signatureContextKey.withIrTypeKey(typeKey)
      val realDecl =
        realDeclaration
          ?: lookupRealDeclaration(
            callableMetadata.isPropertyAccessor,
            callableMetadata.function,
          )
      val lazyParams = memoize { callableMetadata.function.parameters() }
      val computedInlinedValue =
        inlinedValue
          ?: if (computeInlinedValue && context.options.enableProviderInlining) {
            IrInlinedProvider.fromProviderFactory(
              annotations = callableMetadata.annotations,
              parameters = lazyParams.value,
              realDeclaration = realDecl,
            )
          } else {
            null
          }
      return Metro(
        factoryClass = clazz,
        typeKey = typeKey,
        contextualTypeKey = transformedContextKey,
        rawTypeKey = rawTypeKey,
        callableMetadata = callableMetadata,
        realDeclaration = realDecl,
        parametersLazy = lazyParams,
        signatureCarrier = signatureCarrier,
        companionMode = companionMode,
        inlinedValue = computedInlinedValue,
      )
    }

    /**
     * Maps the signature carrier's copies of the provider function's type parameters onto
     * [IrCallableMetadata.functionTypeParameters]. Carriers declare the container's copies first,
     * so the function's copies are the trailing ones.
     */
    private fun signatureFunctionTypeParameterRemapper(
      factoryClass: IrClass,
      signatureFunction: IrSimpleFunction,
      callableMetadata: IrCallableMetadata,
    ): TypeRemapper {
      val functionTypeParameters = callableMetadata.functionTypeParameters
      if (functionTypeParameters.isEmpty()) {
        return NOOP_TYPE_REMAPPER
      }
      val containerTypeParameterCount = factoryClass.parentAsClass.typeParameters.size
      val signatureTypeParameters =
        signatureFunction.typeParameters.drop(containerTypeParameterCount)
      check(signatureTypeParameters.size == functionTypeParameters.size) {
        "Signature carrier ${signatureFunction.name} declares ${signatureTypeParameters.size} " +
          "function type parameters but ${callableMetadata.callableId} has " +
          "${functionTypeParameters.size}"
      }
      return typeRemapperFor(
        signatureTypeParameters.zip(functionTypeParameters).associate { (signature, source) ->
          signature.symbol to source.defaultType
        },
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
