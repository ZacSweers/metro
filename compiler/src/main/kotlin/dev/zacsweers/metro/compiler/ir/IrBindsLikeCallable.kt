// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.appendLineWithUnderlinedRanges
import dev.zacsweers.metro.compiler.graph.LocationDiagnostic
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.remapTypes
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.name.CallableId

internal sealed interface BindsLikeCallable : IrBindingContainerCallable {
  val callableMetadata: IrCallableMetadata
  val callableId: CallableId
    get() = callableMetadata.callableId

  val function: IrSimpleFunction
    get() = callableMetadata.function
}

@Poko
internal class BindsCallable(
  override val callableMetadata: IrCallableMetadata,
  val source: IrTypeKey?,
  /**
   * The canonical typeKey for this binding. For `@IntoSet`/`@IntoMap` bindings, this includes the
   * unique `@MultibindingElement` qualifier. For non-multibinding binds, this equals [rawTarget].
   */
  override val typeKey: IrTypeKey,
  /** The raw target type key without multibinding transformation. Used for diagnostics. */
  val rawTarget: IrTypeKey,
  @Poko.Skip private val parametersLazy: Lazy<Parameters>,
  /**
   * The types [withFunctionTypeArguments] substituted for [functionTypeParameters], in the same
   * order. It's null for a callable that isn't an applied view.
   */
  val functionTypeArguments: List<IrType>? = null,
  /**
   * Substitutes the container's type parameters as [remapTypes] did. It also applies to the bounds
   * of [functionTypeParameters]. It's null when no container substitution happened.
   */
  @Poko.Skip val containerTypeRemapper: TypeRemapper? = null,
) : BindsLikeCallable {

  /**
   * The binds function's own type parameters. They're empty for ordinary binds.
   *
   * [source], [typeKey], [rawTarget], and the alias parameters mention exactly these symbols for
   * function-level type parameters. This holds for callables declared in this compilation and for
   * ones read from another module's mirror. Container substitution through [remapTypes] leaves them
   * in place.
   *
   * An applied view from [withFunctionTypeArguments] still lists the declaration's type parameters
   * here. Its types no longer mention them, and [functionTypeArguments] holds what replaced them.
   */
  val functionTypeParameters: List<IrTypeParameter>
    get() = callableMetadata.functionTypeParameters

  /**
   * True if the binds function declares its own type parameters and they haven't been applied yet.
   * It's false for an applied view.
   */
  val isGeneric: Boolean
    get() = functionTypeParameters.isNotEmpty() && functionTypeArguments == null

  /**
   * Caches the alias for this callable's concrete types. Remapped callables own separate aliases.
   * Parameterless binds use constructor bindings.
   */
  val aliasBinding: IrBinding.Alias by memoize {
    IrBinding.Alias(
      typeKey = typeKey,
      aliasedType = checkNotNull(source) { "Parameterless binds use constructor bindings" },
      bindsCallable = this,
      parameters = parametersLazy.value,
    )
  }

  /**
   * Resolves the source declaration for this callable.
   *
   * @return A pair of (declaration, isContributed) or null if the function is null. If
   *   isContributed is true, the declaration is the source class that contributed this binding.
   */
  // TODO also report target scopes?
  fun resolveSourceDeclaration(): Pair<IrDeclarationWithName, Boolean> {
    val ir = function
    val resolvedIr = ir.overriddenSymbolsSequence().lastOrNull()?.owner ?: ir
    val isMetroContribution =
      resolvedIr.parentClassOrNull?.hasAnnotation(Symbols.ClassIds.metroContribution) == true
    return if (isMetroContribution) {
      // If it's a contribution, the source is
      // SourceClass.MetroContributionScopeName.bindingFunction
      //                                        ^^^
      resolvedIr.parentAsClass.parentAsClass to true
    } else {
      ir to false
    }
  }

  /** Substitutes the container's type parameters through [remapper]. */
  fun remapTypes(remapper: TypeRemapper): BindsCallable {
    if (functionTypeArguments != null) {
      reportCompilerBug(
        "Container substitution must happen before function type arguments are applied to " +
          "$callableId",
      )
    }

    return BindsCallable(
      callableMetadata = callableMetadata,
      source = source?.remapTypes(remapper),
      typeKey = typeKey.remapTypes(remapper),
      rawTarget = rawTarget.remapTypes(remapper),
      parametersLazy = memoize { parametersLazy.value.remapTypes(remapper) },
      containerTypeRemapper = remapper,
    )
  }

  /**
   * Returns an applied view of this generic binds callable with [arguments] substituted for
   * [functionTypeParameters], in the same order. The view's keys, parameters, and [aliasBinding]
   * are concrete.
   */
  fun withFunctionTypeArguments(arguments: List<IrType>): BindsCallable {
    if (!isGeneric) {
      reportCompilerBug("Can't apply function type arguments to non-generic binds $callableId")
    }

    if (arguments.size != functionTypeParameters.size) {
      reportCompilerBug(
        "Binds $callableId declares ${functionTypeParameters.size} type parameters but got " +
          "${arguments.size} arguments",
      )
    }

    val functionRemapper =
      typeRemapperFor(
        functionTypeParameters.zip(arguments).associate { (typeParameter, argument) ->
          typeParameter.symbol to argument
        },
      )

    return BindsCallable(
      callableMetadata = callableMetadata,
      source = source?.remapTypes(functionRemapper),
      typeKey = typeKey.remapTypes(functionRemapper),
      rawTarget = rawTarget.remapTypes(functionRemapper),
      parametersLazy = memoize { parametersLazy.value.remapTypes(functionRemapper) },
      functionTypeArguments = arguments,
      containerTypeRemapper = containerTypeRemapper,
    )
  }

  /** Renders a [LocationDiagnostic] for this callable. */
  fun renderLocationDiagnostic(
    short: Boolean,
    shortLocation: Boolean,
    parameters: Parameters,
  ): LocationDiagnostic {
    val (sourceDeclaration, isContributed) = resolveSourceDeclaration()

    val location = sourceDeclaration.renderSourceLocation(short = shortLocation)
    val unknownLocationContext =
      if (location == null) sourceDeclaration.toUnknownLocationContext(typeKey) else null

    val description = buildString {
      if (isContributed) {
        val sourceName = (sourceDeclaration as IrDeclarationParent).kotlinFqName.asString()
        val renderedType =
          typeKey.renderForDiagnostic(
            short = short,
            includeQualifier = true,
            useOriginalQualifier = true,
          )
        val content = "$sourceName contributes a binding of $renderedType"
        appendLineWithUnderlinedRanges(
          content,
          listOf(
            0 until sourceName.length,
            content.length - renderedType.length until content.length,
          ),
        )
      } else {
        renderForDiagnostic(
          declaration = function,
          short = short,
          typeKey = rawTarget,
          annotations = callableMetadata.annotations,
          parameters = parameters,
          isProperty = callableMetadata.isPropertyAccessor,
          underlineTypeKey = true,
        )
      }
    }
    val span =
      if (isContributed) {
        sourceDeclaration.toNameDiagnosticSpan(shortDisplayPath = shortLocation)
      } else {
        null
      }
    return LocationDiagnostic(
      location = location ?: LocationDiagnostic.NO_SOURCE_LOCATION,
      description = description,
      span = span,
      locationContext = unknownLocationContext?.description,
      notes = unknownLocationContext?.notes.orEmpty(),
    )
  }
}

@Poko
internal class MultibindsCallable(
  override val callableMetadata: IrCallableMetadata,
  override val typeKey: IrTypeKey,
) : BindsLikeCallable {
  fun remapTypes(remapper: TypeRemapper): MultibindsCallable {
    return MultibindsCallable(
      callableMetadata = callableMetadata,
      typeKey = typeKey.remapTypes(remapper),
    )
  }
}

@Poko
internal class BindsOptionalOfCallable(
  override val callableMetadata: IrCallableMetadata,
  override val typeKey: IrTypeKey,
) : BindsLikeCallable {
  fun remapTypes(remapper: TypeRemapper): BindsOptionalOfCallable {
    return BindsOptionalOfCallable(
      callableMetadata = callableMetadata,
      typeKey = typeKey.remapTypes(remapper),
    )
  }
}

context(context: IrMetroContext)
internal fun MetroSimpleFunction.toBindsCallable(
  isInterop: Boolean,
  callableMetadata: IrCallableMetadata = ir.irCallableMetadata(annotations, isInterop),
): BindsCallable {
  // Metadata reconstruction restores the container's type parameters for later specialization.
  val sourceFunction = callableMetadata.function
  val rawTarget = IrContextualTypeKey.from(sourceFunction).typeKey
  val typeKey = rawTarget.transformIfIntoMultibinding(callableMetadata.annotations)
  val nonDispatchParameters = sourceFunction.nonDispatchParameters
  val source =
    when (nonDispatchParameters.size) {
      0 -> null
      1 -> IrContextualTypeKey.from(nonDispatchParameters.single()).typeKey
      else -> reportCompilerBug("@Binds declarations should have at most one source parameter: $ir")
    }
  return BindsCallable(
    callableMetadata = callableMetadata,
    source = source,
    typeKey = typeKey,
    rawTarget = rawTarget,
    parametersLazy = memoize { callableMetadata.function.parameters() },
  )
}

context(context: IrMetroContext)
internal fun MetroSimpleFunction.toMultibindsCallable(
  isInterop: Boolean,
  callableMetadata: IrCallableMetadata = ir.irCallableMetadata(annotations, isInterop),
): MultibindsCallable {
  return MultibindsCallable(
    callableMetadata,
    IrContextualTypeKey.from(callableMetadata.function, patchMutableCollections = isInterop)
      .typeKey,
  )
}

context(context: IrMetroContext)
internal fun MetroSimpleFunction.toBindsOptionalOfCallable(
  callableMetadata: IrCallableMetadata = ir.irCallableMetadata(annotations, isInterop = true),
): BindsOptionalOfCallable {
  // Wrap this in a Java Optional
  // TODO what if we support other optionals?
  val targetType =
    IrContextualTypeKey.from(callableMetadata.function, patchMutableCollections = true).typeKey
  val wrapped = context.metroSymbols.javaOptional.typeWith(targetType.type)
  val wrappedContextKey = targetType.copy(type = wrapped)

  return BindsOptionalOfCallable(
    callableMetadata,
    wrappedContextKey,
  )
}
