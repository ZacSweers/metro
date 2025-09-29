package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.graph.WrappedType
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.coneTypeOrNull

/**
 * Validates that a type is not a lazy-wrapped assisted factory or other disallowed injection site
 * type.
 *
 * @param typeRef The type reference to check
 * @param source The source element for error reporting
 * @return true if validation fails (error was reported), false if validation passes
 */
context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun validateInjectionSiteType(
  session: FirSession,
  typeRef: FirTypeRef,
  qualifier: MetroFirAnnotation?,
  source: KtSourceElement?,
): Boolean {
  val type = typeRef.coneTypeOrNull ?: return true
  val contextKey = type.asFirContextualTypeKey(session, qualifier, false)

  if (contextKey.isWrappedInLazy) {
    checkLazyAssistedFactory(session, contextKey, typeRef, source)
  } else if (contextKey.isLazyWrappedInProvider) {
    checkProviderOfLazy(contextKey, typeRef, source)
  }

  // Check if we're directly injecting a qualifier type
  if (qualifier == null) {
    val clazz = type.classLikeLookupTagIfAny?.toClassSymbol(session) ?: return false
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
