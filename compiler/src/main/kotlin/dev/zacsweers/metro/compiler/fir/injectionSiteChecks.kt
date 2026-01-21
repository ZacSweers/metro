// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.OptionalBindingBehavior
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
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.FirTypeRef
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
  isAccessor: Boolean = false,
  isOptionalBinding: Boolean = false,
  hasDefault: Boolean = false,
): Boolean {
  val type = typeRef.coneTypeOrNull ?: return true
  val contextKey = type.asFirContextualTypeKey(session, qualifier, false)

  if (contextKey.isWrappedInLazy) {
    checkLazyAssistedFactory(session, contextKey, typeRef, source)
  } else if (contextKey.isLazyWrappedInProvider) {
    checkProviderOfLazy(contextKey, typeRef, source, isAccessor)
  }

  // Check if we're directly injecting a qualifier type
  if (qualifier == null) {
    val clazz = type.classLikeLookupTagIfAny?.toClassSymbol(session) ?: return false

    if (clazz.classKind.isObject) {
      // Injecting a plain object doesn't really make sense when it's a singleton
      reporter.reportOn(
        typeRef.source ?: source,
        MetroDiagnostics.SUSPICIOUS_OBJECT_INJECTION_WARNING,
        "Suspicious injection of an unqualified object type '${clazz.classId.asFqNameString()}'. This is probably unnecessary or unintentional.",
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

        val message = buildString {
          val fqName = clazz.classId.asFqNameString()
          append(
            "[Metro/InvalidBinding] '$fqName' uses assisted injection and cannot be injected directly into 'test.ExampleGraph.exampleClass'. You must inject a corresponding @AssistedFactory type or provide a qualified instance on the graph instead."
          )
          if (nestedFactory != null) {
            appendLine()
            appendLine()
            appendLine("(Hint)")
            appendLine(
              "It looks like the @AssistedFactory for '$fqName' may be '${nestedFactory.classId.asFqNameString()}'."
            )
          }
        }
        reporter.reportOn(
          typeRef.source ?: source,
          MetroDiagnostics.ASSISTED_INJECTION_ERROR,
          message,
        )
      }
    }
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
      OptionalBindingBehavior.DISABLED if isOptionalBinding -> {
        reporter.reportOn(
          source,
          MetroDiagnostics.OPTIONAL_BINDING_ERROR,
          "@OptionalBinding is disabled in this project.",
        )
      }
      OptionalBindingBehavior.REQUIRE_OPTIONAL_BINDING -> {
        // Ensure default
        ensureHasDefault()
      }
      // If it's the default, the annotation is redundant. Just a warning
      OptionalBindingBehavior.DEFAULT -> {
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

context(context: CheckerContext, reporter: DiagnosticReporter)
private fun checkLazyAssistedFactory(
  session: FirSession,
  contextKey: FirContextualTypeKey,
  typeRef: FirTypeRef,
  source: KtSourceElement?,
) {
  val canonicalType = contextKey.typeKey.type
  val canonicalClass = canonicalType.toClassSymbol(session)

  if (
    canonicalClass != null &&
      canonicalClass.isAnnotatedWithAny(session, session.classIds.assistedFactoryAnnotations)
  ) {
    reporter.reportOn(
      typeRef.source ?: source,
      MetroDiagnostics.ASSISTED_FACTORIES_CANNOT_BE_LAZY,
      canonicalClass.name.asString(),
      canonicalClass.classId.asFqNameString(),
    )
  }
}

context(context: CheckerContext, reporter: DiagnosticReporter)
private fun checkProviderOfLazy(
  contextKey: FirContextualTypeKey,
  typeRef: FirTypeRef,
  source: KtSourceElement?,
  isAccessor: Boolean,
) {
  // Check if this is a non-metro provider + kotlin lazy. We only support either all dagger or all
  // metro
  val providerType = contextKey.wrappedType as WrappedType.Provider
  val lazyType = providerType.innerType as WrappedType.Lazy
  val providerIsMetro = providerType.providerType == Symbols.ClassIds.metroProvider
  val lazyIsStdLib = lazyType.lazyType == Symbols.ClassIds.Lazy
  if (!providerIsMetro || !lazyIsStdLib) {
    reporter.reportOn(
      typeRef.source ?: source,
      MetroDiagnostics.PROVIDERS_OF_LAZY_MUST_BE_METRO_ONLY,
      providerType.providerType.asString(),
      lazyType.lazyType.asString(),
    )
  }
}
