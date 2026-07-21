// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.circuit.CircuitDiagnostics.CIRCUIT_INJECT_ERROR
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classArgument
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.generators.findSamFunction
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.resolveClassId
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.name.ClassId

private data class CircuitFunctionFactory(
  val function: FirNamedFunctionSymbol,
  val target: CircuitCodegenTarget,
  val factoryClassId: ClassId,
)

/** Whether the `@CircuitInject` site is a Presenter or UI. */
private enum class CircuitInjectSiteType {
  PRESENTER,
  UI,
}

/**
 * Validates parameters for a `@CircuitInject` site (function or class constructor).
 *
 * Rules:
 * - CircuitContext is never allowed (it's factory-level only)
 * - Navigator is presenter-only
 * - Modifier and CircuitUiState subtypes are UI-only
 */
context(context: CheckerContext, reporter: DiagnosticReporter)
private fun validateCircuitInjectParams(
  target: CircuitCodegenTarget,
  siteType: CircuitInjectSiteType?,
  params: List<FirValueParameter>,
  fallbackSource: KtSourceElement,
  circuitSymbols: CircuitSymbols.Fir,
  siteLabel: String,
) {
  val annotationName = target.annotationName
  for (param in params) {
    val paramClassId = param.returnTypeRef.coneType.classId ?: continue

    // CircuitContext is factory-level only — never allowed
    if (
      target == CircuitCodegenTarget.CIRCUIT && circuitSymbols.isCircuitContextType(paramClassId)
    ) {
      reporter.reportOn(
        param.source ?: fallbackSource,
        CIRCUIT_INJECT_ERROR,
        "@$annotationName $siteLabel cannot have a CircuitContext parameter. CircuitContext is only available at the factory level.",
      )
    }

    if (siteType == null) continue

    // Navigator is presenter-only
    if (
      target == CircuitCodegenTarget.CIRCUIT &&
        siteType == CircuitInjectSiteType.UI &&
        circuitSymbols.isNavigatorType(paramClassId)
    ) {
      reporter.reportOn(
        param.source ?: fallbackSource,
        CIRCUIT_INJECT_ERROR,
        "@$annotationName UI $siteLabel cannot have a Navigator parameter. Navigator is only for presenters.",
      )
    }

    // Modifier and CircuitUiState are UI-only
    if (siteType == CircuitInjectSiteType.PRESENTER) {
      if (circuitSymbols.isModifierType(paramClassId)) {
        reporter.reportOn(
          param.source ?: fallbackSource,
          CIRCUIT_INJECT_ERROR,
          "@$annotationName ${target.presenterName} $siteLabel cannot have a Modifier parameter. Modifier is only for UI.",
        )
      }
      if (circuitSymbols.isUiStateType(paramClassId, target)) {
        reporter.reportOn(
          param.source ?: fallbackSource,
          CIRCUIT_INJECT_ERROR,
          "@$annotationName ${target.presenterName} $siteLabel cannot have a ${target.uiStateClassId.shortClassName} parameter. State parameters are only for UI.",
        )
      }
    }
  }
}

context(context: CheckerContext, reporter: DiagnosticReporter)
private fun validateUiFunctionParams(
  target: CircuitCodegenTarget,
  modifierParams: List<FirValueParameter>,
  stateParams: List<FirValueParameter>,
  fallbackSource: KtSourceElement,
) {
  val annotationName = target.annotationName
  if (modifierParams.size > 1) {
    reporter.reportOn(
      modifierParams[1].source ?: fallbackSource,
      CIRCUIT_INJECT_ERROR,
      "@$annotationName ${target.uiName} functions may have only one Modifier parameter.",
    )
  }
  if (stateParams.size > 1) {
    reporter.reportOn(
      stateParams[1].source ?: fallbackSource,
      CIRCUIT_INJECT_ERROR,
      "@$annotationName ${target.uiName} functions may have only one ${target.uiStateClassId.shortClassName} parameter.",
    )
  }
  for (param in modifierParams + stateParams) {
    if (!param.returnTypeRef.coneType.isMarkedNullable) continue
    reporter.reportOn(
      param.source ?: fallbackSource,
      CIRCUIT_INJECT_ERROR,
      "@$annotationName ${target.uiName} framework parameters must be non-null.",
    )
  }
}

/** FIR checker for `@CircuitInject` annotation usage on classes. */
internal object CircuitInjectClassChecker : FirClassChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    context(context.session.compatContext) {
      for (target in CircuitCodegenTarget.entries) {
        if (declaration.hasAnnotation(target.injectAnnotation, context.session)) {
          checkImpl(declaration, target)
        }
      }
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkImpl(declaration: FirClass, target: CircuitCodegenTarget) {
    val source = declaration.source ?: return
    val session = context.session
    val classIds = session.classIds
    val circuitSymbols = session.circuitFirSymbols ?: return

    val annotationName = target.annotationName

    // @CircuitInject on @AssistedInject class — should be on the @AssistedFactory instead
    if (declaration.isAnnotatedWithAny(session, classIds.assistedInjectAnnotations)) {
      val hasNestedFactory = hasNestedAssistedFactory(declaration, session)
      if (hasNestedFactory) {
        reporter.reportOn(
          source,
          CIRCUIT_INJECT_ERROR,
          "@$annotationName with @AssistedInject must be placed on the nested @AssistedFactory interface, not the class itself.",
        )
      } else {
        reporter.reportOn(
          source,
          CIRCUIT_INJECT_ERROR,
          "@AssistedInject class with @$annotationName must have a nested @AssistedFactory-annotated interface.",
        )
      }
      return
    } else if (declaration.isAnnotatedWithAny(session, classIds.assistedFactoryAnnotations)) {
      validateAssistedFactory(declaration, target, source, circuitSymbols)
      return
    }

    // Determine site type for param validation
    val siteType =
      when {
        circuitSymbols.isUiType(declaration, target) -> CircuitInjectSiteType.UI
        circuitSymbols.isPresenterType(declaration, target) -> CircuitInjectSiteType.PRESENTER
        else -> null
      }

    // For non-assisted classes, validate supertypes
    if (siteType == null) {
      reporter.reportOn(
        source,
        CIRCUIT_INJECT_ERROR,
        "@$annotationName-annotated class must implement ${target.presenterName} or ${target.uiName}.",
      )
    }

    // @CircuitInject classes must use @Inject (or be objects)
    if (declaration.classKind != ClassKind.OBJECT) {
      val hasInject = declaration.isAnnotatedWithAny(session, classIds.injectAnnotations)
      @OptIn(DirectDeclarationsAccess::class)
      val hasInjectConstructor =
        declaration.declarations.filterIsInstance<FirConstructor>().any {
          it.isAnnotatedWithAny(session, classIds.allInjectAnnotations)
        }
      if (!hasInject && !hasInjectConstructor) {
        // Check if the constructor has circuit-provided params to give a more specific message
        @OptIn(DirectDeclarationsAccess::class)
        val hasCircuitParams =
          declaration.declarations.filterIsInstance<FirConstructor>().any { ctor ->
            ctor.valueParameters.any { param ->
              val paramClassId = param.returnTypeRef.coneType.classId ?: return@any false
              when (target) {
                CircuitCodegenTarget.CIRCUIT ->
                  circuitSymbols.isNavigatorType(paramClassId) ||
                    circuitSymbols.isScreenType(paramClassId, target) ||
                    circuitSymbols.isModifierType(paramClassId) ||
                    circuitSymbols.isUiStateType(paramClassId, target)
                CircuitCodegenTarget.SUBCIRCUIT -> circuitSymbols.isScreenType(paramClassId, target)
              }
            }
          }
        val message =
          when (target) {
            CircuitCodegenTarget.CIRCUIT ->
              if (hasCircuitParams) {
                "@$annotationName-annotated class must also be annotated with @Inject. " +
                  "Circuit-provided parameters (Screen, Navigator, etc.) should use @AssistedInject with @Assisted annotations, " +
                  "or consider using a presenter function instead."
              } else {
                "@$annotationName-annotated class must also be annotated with @Inject. " +
                  "If no dependencies are needed, consider using a presenter function instead."
              }
            CircuitCodegenTarget.SUBCIRCUIT ->
              if (hasCircuitParams) {
                "@$annotationName-annotated class must also be annotated with @Inject. " +
                  "SubCircuit-provided screen parameters should use @AssistedInject with @Assisted annotations."
              } else {
                "@$annotationName-annotated class must also be annotated with @Inject."
              }
          }
        reporter.reportOn(source, CIRCUIT_INJECT_ERROR, message)
        return
      }
    }

    @OptIn(DirectDeclarationsAccess::class)
    for (constructor in declaration.declarations.filterIsInstance<FirConstructor>()) {
      validateCircuitInjectParams(
        target,
        siteType,
        constructor.valueParameters,
        source,
        circuitSymbols,
        "classes",
      )
    }
  }

  @OptIn(DirectDeclarationsAccess::class)
  private fun hasNestedAssistedFactory(declaration: FirClass, session: FirSession): Boolean {
    return declaration.declarations.filterIsInstance<FirClass>().any {
      it.isAnnotatedWithAny(session, session.classIds.assistedFactoryAnnotations)
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun validateAssistedFactory(
    declaration: FirClass,
    target: CircuitCodegenTarget,
    source: KtSourceElement,
    circuitSymbols: CircuitSymbols.Fir,
  ) {
    val session = context.session
    val annotationName = target.annotationName
    val containingClassId = declaration.symbol.getContainingClassSymbol()?.classId
    val parentIsPresenter =
      containingClassId?.let {
        circuitSymbols.isOrImplements(it, target.presenterClassId)
      } == true
    val parentIsUi =
      containingClassId?.let { circuitSymbols.isOrImplements(it, target.uiClassId) } == true
    if (!parentIsPresenter && !parentIsUi) {
      reporter.reportOn(
        source,
        CIRCUIT_INJECT_ERROR,
        "@$annotationName @AssistedFactory must be nested inside the target ${target.presenterName} or ${target.uiName} class.",
      )
      return
    }

    val assistedFunction = declaration.symbol.findSamFunction(session) ?: return
    val assistedTargetClassId = assistedFunction.resolvedReturnTypeRef.coneType.classId
    if (assistedTargetClassId != containingClassId) {
      reporter.reportOn(
        assistedFunction.resolvedReturnTypeRef.source ?: assistedFunction.source ?: source,
        CIRCUIT_INJECT_ERROR,
        "@$annotationName @AssistedFactory function must return its containing ${target.presenterName} or ${target.uiName} class.",
      )
      return
    }

    if (target != CircuitCodegenTarget.SUBCIRCUIT) return
    if (assistedFunction.valueParameterSymbols.isEmpty()) return

    val annotation =
      declaration.symbol.annotationsIn(session, setOf(target.injectAnnotation)).firstOrNull()
        ?: return
    val typeResolver = MetroFirTypeResolver.Factory(session).create(declaration.symbol) ?: return
    val screenClassId =
      annotation
        .classArgument(session, CircuitNames.screen, index = 0)
        ?.resolveClassId(typeResolver) ?: return
    val screenParams =
      assistedFunction.valueParameterSymbols.filter {
        it.resolvedReturnTypeRef.coneType.classId == screenClassId
      }
    if (screenParams.size == 1 && assistedFunction.valueParameterSymbols.size == 1) return

    reporter.reportOn(
      assistedFunction.source ?: source,
      CIRCUIT_INJECT_ERROR,
      "@$annotationName @AssistedFactory function may only have one parameter matching the annotated screen type.",
    )
  }
}

/** FIR checker for `@CircuitInject`-annotated functions. */
internal object CircuitInjectCallableChecker :
  FirCallableDeclarationChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    if (declaration !is FirFunction) return

    val session = context.session
    for (target in CircuitCodegenTarget.entries) {
      if (!declaration.hasAnnotation(target.injectAnnotation, session)) continue
      checkTarget(declaration, target)
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkTarget(declaration: FirFunction, target: CircuitCodegenTarget) {
    val session = context.session
    val source = declaration.source ?: return
    val circuitSymbols = session.circuitFirSymbols ?: return
    val annotationName = target.annotationName

    // Check the final generated ClassId so capitalization and cross-target suffix collisions are
    // diagnosed before declaration generation.
    val annotatedSymbols = CircuitFirExtension.findCircuitInjectSymbols(session)
    val functionFactories =
      CircuitCodegenTarget.entries.flatMap { codegenTarget ->
        CircuitFirExtension.findCircuitInjectFunctions(annotatedSymbols, session, codegenTarget)
          .map { function ->
            CircuitFunctionFactory(
              function,
              codegenTarget,
              ClassId(
                function.callableId.packageName,
                codegenTarget.functionFactoryName(function.name.asString()),
              ),
            )
          }
      }
    val factoryClassId =
      ClassId(
        declaration.symbol.callableId.packageName,
        target.functionFactoryName(declaration.symbol.name.asString()),
      )
    val conflicts = functionFactories.filter { it.factoryClassId == factoryClassId }

    // TODO this seems expensive to do there. Maybe FirLanguageVersionSettingsChecker?
    if (conflicts.size > 1 && conflicts.any { it.function == declaration.symbol }) {
      val isSameTargetAndName = conflicts.all {
        it.target == target && it.function.name == declaration.symbol.name
      }
      if (isSameTargetAndName) {
        val codegenName =
          if (target == CircuitCodegenTarget.CIRCUIT) {
            "Circuit FIR code gen"
          } else {
            "SubCircuit FIR code gen"
          }
        reporter.reportOn(
          declaration.source,
          CIRCUIT_INJECT_ERROR,
          "Multiple @$annotationName-annotated functions named ${declaration.symbol.name} were found. " +
            "This will create conflicts in $codegenName, please deduplicate names.",
        )
      } else {
        reporter.reportOn(
          declaration.source,
          CIRCUIT_INJECT_ERROR,
          "@$annotationName function ${declaration.symbol.name} generates ${factoryClassId.shortClassName}, " +
            "which conflicts with another Circuit codegen function. Rename one of the functions.",
        )
      }
    }

    val returnTypeRef = declaration.returnTypeRef
    val returnType = returnTypeRef.coneType
    val modifierParams =
      declaration.valueParameters.filter { param ->
        val paramClassId = param.returnTypeRef.coneType.classId ?: return@filter false
        circuitSymbols.isModifierType(paramClassId)
      }
    val stateParams =
      declaration.valueParameters.filter { param ->
        val paramClassId = param.returnTypeRef.coneType.classId ?: return@filter false
        circuitSymbols.isUiStateType(paramClassId, target)
      }
    val hasModifier = modifierParams.isNotEmpty()

    if (target == CircuitCodegenTarget.SUBCIRCUIT) {
      validateUiFunctionParams(target, modifierParams, stateParams, source)
      if (!hasModifier) {
        reporter.reportOn(
          source,
          CIRCUIT_INJECT_ERROR,
          "@SubCircuitInject @Composable SubUi functions must have a Modifier parameter.",
        )
      }
      validateCircuitInjectParams(
        target,
        CircuitInjectSiteType.UI,
        declaration.valueParameters,
        source,
        circuitSymbols,
        "functions",
      )
      return
    }

    // Check for implicit return type on presenter functions.
    // If the return type is implicit and there's a Modifier param, we can assume it's UI (Unit),
    // so only flag when there's no Modifier param.
    if (returnTypeRef.source?.kind is KtFakeSourceElementKind.ImplicitTypeRef && !hasModifier) {
      reporter.reportOn(
        source,
        CIRCUIT_INJECT_ERROR,
        "@CircuitInject presenter functions must have an explicit CircuitUiState subtype return " +
          "type and cannot be implicit.",
      )
      return
    }

    val siteType: CircuitInjectSiteType
    if (returnType.isUnit) {
      siteType = CircuitInjectSiteType.UI
      validateUiFunctionParams(target, modifierParams, stateParams, source)
      if (!hasModifier) {
        reporter.reportOn(
          source,
          CIRCUIT_INJECT_ERROR,
          "@CircuitInject @Composable functions that return Unit are treated as UI functions and must have a Modifier parameter. " +
            "If this is a presenter, add a CircuitUiState return type.",
        )
      }
    } else {
      siteType = CircuitInjectSiteType.PRESENTER
      returnType.classLikeLookupTagIfAny?.let { tag ->
        val returnClassId = tag.classId
        if (!circuitSymbols.isUiStateType(returnClassId, target)) {
          reporter.reportOn(
            source,
            CIRCUIT_INJECT_ERROR,
            "@CircuitInject @Composable presenter functions must return a CircuitUiState subtype.",
          )
        }
      }
    }

    validateCircuitInjectParams(
      target,
      siteType,
      declaration.valueParameters,
      source,
      circuitSymbols,
      "functions",
    )
  }
}
