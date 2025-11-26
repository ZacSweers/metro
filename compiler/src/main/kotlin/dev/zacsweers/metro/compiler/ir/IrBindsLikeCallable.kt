// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.appendLineWithUnderlinedContent
import dev.zacsweers.metro.compiler.graph.LocationDiagnostic
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.typeWith
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
  val source: IrTypeKey,
  /**
   * The canonical typeKey for this binding. For `@IntoSet`/`@IntoMap` bindings, this includes the
   * unique `@MultibindingElement` qualifier. For non-multibinding binds, this equals [rawTarget].
   */
  override val typeKey: IrTypeKey,
  /** The raw target type key without multibinding transformation. Used for diagnostics. */
  val rawTarget: IrTypeKey,
) : BindsLikeCallable {

  /**
   * Resolves the source declaration for this callable.
   *
   * @return A pair of (declaration, isContributed) or null if the function is null. If
   *   isContributed is true, the declaration is the source class that contributed this binding.
   */
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

  /** Renders a [LocationDiagnostic] for this callable. */
  fun renderLocationDiagnostic(short: Boolean, parameters: Parameters): LocationDiagnostic {
    val (sourceDeclaration, isContributed) = resolveSourceDeclaration()

    val location =
      sourceDeclaration.locationOrNull()?.render(short)
        ?: "<unknown location, likely a separate compilation>"

    val description = buildString {
      if (isContributed) {
        append((sourceDeclaration as IrDeclarationParent).kotlinFqName)
        append(" contributes a binding of ")
        appendLineWithUnderlinedContent(typeKey.render(short = short, includeQualifier = true))
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
    return LocationDiagnostic(location, description)
  }
}

@Poko
internal class MultibindsCallable(
  override val callableMetadata: IrCallableMetadata,
  override val typeKey: IrTypeKey,
) : BindsLikeCallable

@Poko
internal class BindsOptionalOfCallable(
  override val callableMetadata: IrCallableMetadata,
  override val typeKey: IrTypeKey,
) : BindsLikeCallable

context(context: IrMetroContext)
internal fun MetroSimpleFunction.toBindsCallable(isInterop: Boolean): BindsCallable {
  val callableMetadata = ir.irCallableMetadata(annotations, isInterop)
  val rawTarget = IrContextualTypeKey.from(ir).typeKey
  val typeKey = rawTarget.transformMultiboundQualifier(callableMetadata.annotations)
  return BindsCallable(
    callableMetadata = callableMetadata,
    source = IrContextualTypeKey.from(ir.nonDispatchParameters.single()).typeKey,
    typeKey = typeKey,
    rawTarget = rawTarget,
  )
}

context(context: IrMetroContext)
internal fun MetroSimpleFunction.toMultibindsCallable(isInterop: Boolean): MultibindsCallable {
  return MultibindsCallable(
    ir.irCallableMetadata(annotations, isInterop),
    IrContextualTypeKey.from(ir, patchMutableCollections = isInterop).typeKey,
  )
}

context(context: IrMetroContext)
internal fun MetroSimpleFunction.toBindsOptionalOfCallable(): BindsOptionalOfCallable {
  // Wrap this in a Java Optional
  // TODO what if we support other optionals?
  val targetType = IrContextualTypeKey.from(ir, patchMutableCollections = true).typeKey
  val wrapped = context.metroSymbols.javaOptional.typeWith(targetType.type)
  val wrappedContextKey = targetType.copy(type = wrapped)

  return BindsOptionalOfCallable(
    ir.irCallableMetadata(annotations, isInterop = true),
    wrappedContextKey,
  )
}
