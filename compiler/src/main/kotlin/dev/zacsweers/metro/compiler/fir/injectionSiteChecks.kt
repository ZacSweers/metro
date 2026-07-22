// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.MetroOptions.DiagnosticSeverity.ERROR
import dev.zacsweers.metro.compiler.MetroOptions.DiagnosticSeverity.NONE
import dev.zacsweers.metro.compiler.MetroOptions.DiagnosticSeverity.WARN
import dev.zacsweers.metro.compiler.diagnostics.DiagnosticBatch
import dev.zacsweers.metro.compiler.diagnostics.Style
import dev.zacsweers.metro.compiler.diagnostics.buildText
import dev.zacsweers.metro.compiler.diagnostics.invalidAssistedBindingDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.render.DiagnosticRenderer
import dev.zacsweers.metro.compiler.diagnostics.render.RenderProfile
import dev.zacsweers.metro.compiler.diagnostics.textOf
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirFunctionTypeRef
import org.jetbrains.kotlin.fir.types.FirPlaceholderProjection
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirStarProjection
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.coneTypeOrNull

/** Validates a binding ref (anything that can have a qualifier) */
context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun FirBasedSymbol<*>.validateBindingRef(
  annotations: MetroAnnotations<MetroFirAnnotation> =
    metroAnnotations(context.session, setOf(MetroAnnotations.Kind.Qualifier))
) {
  if (annotations.qualifiers.size > 1) {
    for (key in annotations.qualifiers) {
      reporter.reportOn(
        key.fir.source ?: source,
        MetroDiagnostics.BINDING_ERROR,
        "At most one @Qualifier annotation should be be used on a given declaration but found ${annotations.qualifiers.size}.",
      )
    }
  }
}

/** Validates a binding source (anything that can have a map key, source, qualifier, or scope) */
context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun FirBasedSymbol<*>.validateBindingSource(
  annotations: MetroAnnotations<MetroFirAnnotation> =
    metroAnnotations(
      context.session,
      setOf(
        MetroAnnotations.Kind.Qualifier,
        MetroAnnotations.Kind.Scope,
        MetroAnnotations.Kind.MapKey,
        MetroAnnotations.Kind.IntoMap,
      ),
    )
) {
  // Check for 1:1 `@IntoMap`+`@MapKey`
  if (annotations.mapKeys.size > 1) {
    for (key in annotations.mapKeys) {
      reporter.reportOn(
        key.fir.source,
        MetroDiagnostics.MULTIBINDS_ERROR,
        "Only one @MapKey should be be used on a given @IntoMap declaration.",
      )
    }
  } else if (annotations.isIntoMap && annotations.mapKey == null) {
    reporter.reportOn(
      source,
      MetroDiagnostics.MULTIBINDS_ERROR,
      "`@IntoMap` declarations must define a @MapKey annotation.",
    )
  }

  // Check scopes
  if (annotations.scopes.size > 1) {
    for (key in annotations.scopes) {
      reporter.reportOn(
        key.fir.source ?: source,
        MetroDiagnostics.BINDING_ERROR,
        "At most one @Scope annotation should be be used on a given declaration but found ${annotations.scopes.size}.",
      )
    }
  }

  validateBindingRef(annotations)
}

/**
 * Validates that a type is not a lazy-wrapped assisted factory or other disallowed injection site
 * type.
 *
 * @param typeRef The type reference to check
 * @param source The source element for error reporting
 * @return true if validation fails (error was reported), false if validation passes
 */
@IgnorableReturnValue
context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun validateInjectionSiteType(
  session: FirSession,
  typeRef: FirTypeRef,
  qualifier: MetroFirAnnotation?,
  source: KtSourceElement?,
  injectionSite: String? = null,
  isAccessor: Boolean = false,
  isOptionalBinding: Boolean = false,
  hasDefault: Boolean = false,
): Boolean {
  val type = typeRef.coneTypeOrNull ?: return true
  val contextKey = type.asFirContextualTypeKey(session, qualifier, false)

  val options = session.metroFirBuiltIns.options
  val usesSuspendWrapper = contextKey.wrappedType.containsSuspendWrapper()
  if (!options.enableSuspendProviders && usesSuspendWrapper) {
    reporter.reportOn(
      typeRef.source ?: source,
      MetroDiagnostics.SUSPEND_PROVIDERS_NOT_ENABLED,
      SUSPEND_PROVIDERS_NOT_ENABLED_MESSAGE,
    )
    return true
  }

  if (contextKey.isWrappedInLazy) {
    checkLazyAssistedFactory(session, contextKey, typeRef, source)
  }
  if (contextKey.wrappedType.containsProviderOfLazy()) {
    checkProviderOfLazy(session, contextKey, typeRef, source)
  }

  if (contextKey.wrappedType !is WrappedType.Canonical) {
    checkDesugaredProviderUse(session, contextKey, typeRef, source)
    checkUnsupportedSuspendMapValue(session, contextKey, typeRef, source)
  }

  val clazz = type.classLikeLookupTagIfAny?.toClassSymbolCompat(session)

  // Object/assisted-injection diagnostics only apply to unqualified injections — qualifying the
  // injection signals an intentional non-default usage.
  if (qualifier == null && clazz != null) {
    if (clazz.classKind.isObject) {
      // Injecting a plain object doesn't really make sense when it's a singleton
      reporter.reportOn(
        typeRef.source ?: source,
        MetroDiagnostics.SUSPICIOUS_OBJECT_INJECTION_WARNING,
        "Suspicious injection of an unqualified object type '${clazz.classId.diagnosticString}'. This is probably unnecessary or unintentional.",
      )
    } else {
      val isAssistedInject =
        clazz.findAssistedInjectConstructors(session, checkClass = true).isNotEmpty()
      if (isAssistedInject) {
        @OptIn(DirectDeclarationsAccess::class)
        val nestedFactory =
          clazz.nestedClasses().find {
            it.isAnnotatedWithAny(session, session.classIds.assistedFactoryAnnotations)
          }
            ?: session.firProvider
              .getFirClassifierContainerFile(clazz.classId)
              .declarations
              .filterIsInstance<FirClass>()
              .find { it.isAnnotatedWithAny(session, session.classIds.assistedFactoryAnnotations) }
              ?.symbol

        val assistedType = buildText {
          val fqName = clazz.classId.asSingleFqName().asString()
          appendType(fqName)
        }
        val assistedFactory = nestedFactory?.let {
          buildText {
            val fqName = it.classId.asSingleFqName().asString()
            appendType(fqName)
          }
        }
        val diagnostic =
          invalidAssistedBindingDiagnostic(
            assistedType = assistedType,
            injectionSite = injectionSite?.let { textOf(it, Style.EMPHASIS) },
            assistedFactory = assistedFactory,
          )
        val prepared = DiagnosticBatch.prepare(listOf(diagnostic)).single()
        val renderedMessage =
          DiagnosticRenderer(RenderProfile.PLAIN)
            .render(prepared.diagnostic, prepared.renderContext)
        val message = renderedMessage.removePrefix("[${diagnostic.id.fullId}] ")
        val reportSource =
          if (injectionSite == null) typeRef.source ?: source else source ?: typeRef.source
        reporter.reportOn(
          reportSource,
          diagnostic.id.factory,
          message,
        )
      }
    }
  }

  // Warn whenever an impl class is injected directly while hidden behind a generated contribution
  // provider — this is qualifier-independent because qualifying the injection still doesn't make
  // the impl a binding on the graph. Skipped for objects (covered above) and assisted-inject
  // classes (the assisted-injection error is more actionable).
  if (
    clazz != null &&
      !clazz.classKind.isObject &&
      clazz.findAssistedInjectConstructors(session, checkClass = true).isEmpty() &&
      clazz.usesContributionProviderPath(session)
  ) {
    val fqName = clazz.classId.diagnosticString
    reporter.reportOn(
      typeRef.source ?: source,
      MetroDiagnostics.NON_EXPOSED_IMPL_TYPE,
      "Directly injecting '$fqName' (which has one or more `@Contributes*` annotations) and will not be " +
        "visible since `generateContributionProviders` is enabled. This is probably a bug! " +
        "Inject the bound supertype instead, or annotate '$fqName' with `@ExposeImplBinding` " +
        "to expose the underlying binding.",
    )
  }

  if (!isAccessor && (isOptionalBinding || hasDefault)) {
    @IgnorableReturnValue
    fun ensureHasDefault(): Boolean {
      return if (!hasDefault) {
        reporter.reportOn(
          typeRef.source ?: source,
          MetroDiagnostics.OPTIONAL_BINDING_ERROR,
          "@OptionalBinding-annotated parameters must have a default value.",
        )
        false
      } else {
        true
      }
    }

    val behavior = session.metroFirBuiltIns.options.optionalBindingBehavior
    when (behavior) {
      // If it's disabled, this annotation isn't gonna do anything. Error because it's def not gonna
      // behave the way they expect
      DISABLED if isOptionalBinding -> {
        reporter.reportOn(
          source,
          MetroDiagnostics.OPTIONAL_BINDING_ERROR,
          "@OptionalBinding is disabled in this project.",
        )
      }
      REQUIRE_OPTIONAL_BINDING -> {
        // Ensure default
        ensureHasDefault()
      }
      // If it's the default, the annotation is redundant. Just a warning
      DEFAULT -> {
        // Ensure there's a default value
        val hasDefault = ensureHasDefault()
        if (hasDefault && isOptionalBinding) {
          reporter.reportOn(
            source,
            MetroDiagnostics.OPTIONAL_BINDING_WARNING,
            "@OptionalBinding is redundant in this project as the presence of a default value is sufficient.",
          )
        }
      }
      else -> {
        // Do nothing
      }
    }
  }

  // Future injection site checks can be added here

  return false
}

private fun FirTypeRef.sourceTypeRef(): FirTypeRef {
  return (this as? FirResolvedTypeRef)?.delegatedTypeRef ?: this
}

private fun FirTypeRef.immediateInnerTypeRef(): FirTypeRef? {
  return when (this) {
    is FirFunctionTypeRef -> returnTypeRef
    is FirUserTypeRef -> singleTypeArgumentRefOrNull()
    else -> null
  }
}

private fun FirUserTypeRef.singleTypeArgumentRefOrNull(): FirTypeRef? {
  val arguments = qualifier.lastOrNull()?.typeArgumentList?.typeArguments ?: return null
  return when (val argument = arguments.singleOrNull()) {
    is FirTypeProjectionWithVariance -> argument.typeRef
    is FirPlaceholderProjection,
    is FirStarProjection,
    null -> null
  }
}

context(context: CheckerContext, reporter: DiagnosticReporter)
private fun checkLazyAssistedFactory(
  session: FirSession,
  contextKey: FirContextualTypeKey,
  typeRef: FirTypeRef,
  source: KtSourceElement?,
) {
  val canonicalType = contextKey.typeKey.type
  val canonicalClass = canonicalType.toClassSymbolCompat(session)

  if (
    canonicalClass != null &&
      canonicalClass.isAnnotatedWithAny(session, session.classIds.assistedFactoryAnnotations)
  ) {
    reporter.reportOn(
      typeRef.source ?: source,
      MetroDiagnostics.ASSISTED_FACTORIES_CANNOT_BE_LAZY,
      canonicalClass.name.asString(),
      canonicalClass.classId.diagnosticString,
    )
  }
}

context(context: CheckerContext, reporter: DiagnosticReporter)
private fun checkProviderOfLazy(
  session: FirSession,
  contextKey: FirContextualTypeKey,
  typeRef: FirTypeRef,
  source: KtSourceElement?,
) {
  // Provider<Lazy<T>> is the one provider/lazy combination that requires both layers to use
  // Metro's intrinsic types. Apply that rule to every adjacent pair in a recursive scalar stack.
  val scalarTypes = generateSequence(contextKey.wrappedType) { it.immediateInnerType() }
  val providerLazyPairs = scalarTypes.mapNotNull { wrappedType ->
    val providerType = wrappedType as? WrappedType.Provider ?: return@mapNotNull null
    val lazyType = providerType.innerType as? WrappedType.Lazy ?: return@mapNotNull null
    providerType to lazyType
  }
  val invalidPair =
    providerLazyPairs.firstOrNull { (providerType, lazyType) ->
      val providerIsMetroOrFunction =
        providerType.providerType == Symbols.ClassIds.metroProvider ||
          (session.metroFirBuiltIns.options.enableFunctionProviders &&
            providerType.providerType == Symbols.ClassIds.function0)
      val lazyIsStdLib = lazyType.lazyType == Symbols.ClassIds.Lazy
      !providerIsMetroOrFunction || !lazyIsStdLib
    } ?: return
  val (providerType, lazyType) = invalidPair
  reporter.reportOn(
    typeRef.source ?: source,
    MetroDiagnostics.PROVIDERS_OF_LAZY_MUST_BE_METRO_ONLY,
    providerType.providerType.asString(),
    lazyType.lazyType.asString(),
  )
}

context(context: CheckerContext, reporter: DiagnosticReporter)
private fun checkDesugaredProviderUse(
  session: FirSession,
  contextKey: FirContextualTypeKey,
  typeRef: FirTypeRef,
  source: KtSourceElement?,
) {
  val options = session.metroFirBuiltIns.options
  val severity = options.desugaredProviderSeverity.resolve(session.isIde())
  if (severity == NONE) return
  val desugaredProvider =
    contextKey.wrappedType.innerTypesSequence.firstOrNull { wrapped ->
      val isDesugaredProvider =
        wrapped is WrappedType.Provider && wrapped.providerType == Symbols.ClassIds.metroProvider
      val isDesugaredSuspendProvider =
        wrapped is WrappedType.SuspendProvider &&
          wrapped.providerType == Symbols.ClassIds.metroSuspendProvider
      isDesugaredProvider || isDesugaredSuspendProvider
    } ?: return
  val (desugaredForm, functionForm) =
    when (desugaredProvider) {
      is WrappedType.SuspendProvider -> "SuspendProvider<T>" to "suspend () -> T"
      else -> "Provider<T>" to "() -> T"
    }
  val factory =
    when (severity) {
      ERROR -> MetroDiagnostics.DESUGARED_PROVIDER_ERROR
      WARN -> MetroDiagnostics.DESUGARED_PROVIDER_WARNING
      else -> return
    }
  reporter.reportOn(
    typeRef.source ?: source,
    factory,
    "Using the desugared `$desugaredForm` type is discouraged. Prefer the function syntax form `$functionForm` instead.",
  )
}

/** Rejects suspend map value forms that the multibinding factories cannot materialize. */
context(context: CheckerContext, reporter: DiagnosticReporter)
private fun checkUnsupportedSuspendMapValue(
  session: FirSession,
  contextKey: FirContextualTypeKey,
  typeRef: FirTypeRef,
  source: KtSourceElement?,
) {
  val offendingValue = contextKey.wrappedType.firstUnsupportedSuspendMapValue() ?: return
  val reportSource =
    typeRef.sourceForSuspendMapValue(contextKey.wrappedType, offendingValue)
      ?: typeRef.source
      ?: source
  val rendered =
    FirContextualTypeKey(contextKey.typeKey, offendingValue)
      .originalType(session)
      .render(short = true)
  reporter.reportOn(reportSource, MetroDiagnostics.UNSUPPORTED_SUSPEND_MAP_VALUE, rendered)
}

/**
 * Returns the map value wrapper that is unsupported, descending scalar wrappers and nested maps.
 */
private fun WrappedType<ConeKotlinType>.firstUnsupportedSuspendMapValue():
  WrappedType<ConeKotlinType>? {
  return when (this) {
    is WrappedType.Canonical -> null
    is WrappedType.Provider -> innerType.firstUnsupportedSuspendMapValue()
    is WrappedType.SuspendProvider -> innerType.firstUnsupportedSuspendMapValue()
    is WrappedType.Lazy -> innerType.firstUnsupportedSuspendMapValue()
    is WrappedType.SuspendLazy -> innerType.firstUnsupportedSuspendMapValue()
    is WrappedType.Map -> {
      val value = valueType
      if (value !is WrappedType.Map && value.hasUnsupportedSuspendWrapperInMapValue()) {
        value
      } else {
        value.firstUnsupportedSuspendMapValue()
      }
    }
  }
}

/**
 * Walks a type ref to the source of [target], descending scalar wrappers and map value positions.
 */
private fun FirTypeRef.sourceForSuspendMapValue(
  root: WrappedType<ConeKotlinType>,
  target: WrappedType<ConeKotlinType>,
): KtSourceElement? {
  var currentTypeRef = sourceTypeRef()
  var currentWrappedType = root
  while (true) {
    if (currentWrappedType === target) return currentTypeRef.source
    val nextWrappedType: WrappedType<ConeKotlinType>?
    val nextTypeRef: FirTypeRef?
    if (currentWrappedType is WrappedType.Map) {
      nextWrappedType = currentWrappedType.valueType
      nextTypeRef = currentTypeRef.mapValueTypeRefOrNull()
    } else {
      nextWrappedType = currentWrappedType.immediateInnerType()
      nextTypeRef = currentTypeRef.immediateInnerTypeRef()
    }
    if (nextWrappedType == null || nextTypeRef == null) return null
    currentWrappedType = nextWrappedType
    currentTypeRef = nextTypeRef.sourceTypeRef()
  }
}

private fun FirTypeRef.mapValueTypeRefOrNull(): FirTypeRef? {
  val userTypeRef = this as? FirUserTypeRef ?: return null
  val arguments = userTypeRef.qualifier.lastOrNull()?.typeArgumentList?.typeArguments ?: return null
  if (arguments.size != 2) return null
  return when (val argument = arguments[1]) {
    is FirTypeProjectionWithVariance -> argument.typeRef
    is FirPlaceholderProjection,
    is FirStarProjection,
    null -> null
  }
}

private fun WrappedType<ConeKotlinType>.hasUnsupportedSuspendMapValue(): Boolean {
  return when (this) {
    is WrappedType.Canonical -> false
    is WrappedType.Provider -> innerType.hasUnsupportedSuspendMapValue()
    is WrappedType.SuspendProvider -> innerType.hasUnsupportedSuspendMapValue()
    is WrappedType.Lazy -> innerType.hasUnsupportedSuspendMapValue()
    is WrappedType.SuspendLazy -> innerType.hasUnsupportedSuspendMapValue()
    is WrappedType.Map -> valueType.hasUnsupportedSuspendWrapperInMapValue()
  }
}

private fun WrappedType<ConeKotlinType>.hasUnsupportedSuspendWrapperInMapValue(): Boolean {
  return when (this) {
    is WrappedType.Canonical -> false
    is WrappedType.Provider -> {
      val wrapsSuspendType =
        innerType is WrappedType.SuspendProvider || innerType is WrappedType.SuspendLazy
      wrapsSuspendType || innerType.hasUnsupportedSuspendWrapperInMapValue()
    }
    is WrappedType.SuspendProvider -> {
      when (innerType) {
        is WrappedType.Canonical -> false
        is WrappedType.Map -> innerType.hasUnsupportedSuspendMapValue()
        else -> true
      }
    }
    is WrappedType.Lazy -> {
      val wrapsSuspendType =
        innerType is WrappedType.SuspendProvider || innerType is WrappedType.SuspendLazy
      wrapsSuspendType || innerType.hasUnsupportedSuspendWrapperInMapValue()
    }
    is WrappedType.SuspendLazy -> true
    is WrappedType.Map -> hasUnsupportedSuspendMapValue()
  }
}
