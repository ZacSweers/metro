// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.KNOWN_KOTLINC_BUG_WARNING
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.coneTypeIfResolved
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isResolved
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.render
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.hilt.HiltSymbols
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirAnnotationChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassLikeSymbol
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.arrayElementType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isPrimitiveArray
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

/** Warns about generic array class literals whose dimensions can change in binary annotations. */
internal object ArrayClassKeyChecker : FirAnnotationChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(expression: FirAnnotation) {
    val source = expression.source ?: return
    if (!expression.isResolved || source.kind != KtRealSourceElementKind) {
      return
    }
    if (context.containingDeclarations.lastOrNull()?.source?.kind != KtRealSourceElementKind) {
      return
    }

    val session = context.session
    val annotationClass =
      expression.toAnnotationClassLikeSymbol(session) as? FirRegularClassSymbol ?: return
    val keyParameters = annotationClass.keyParameters(session)
    if (keyParameters == KeyParameters.NONE) {
      return
    }

    // The outer DI annotation owns nested values, including nested annotations with DI roles.
    val hasKeyParent =
      context.containingElements.any {
        it is FirAnnotation && it !== expression && it.isKeyAnnotation(session)
      }
    if (hasKeyParent) {
      return
    }

    val values =
      ArrayClassKeyValues(session, source) { reportSource, literal ->
        reportArrayClassKey(reportSource, literal)
      }
    values.checkAnnotation(expression, keyParameters = keyParameters)
  }

  /** Source DI declarations own their defaults, even when no source use selects them. */
  object Defaults : FirClassChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
      if (declaration.classKind != ClassKind.ANNOTATION_CLASS) {
        return
      }
      if (declaration.origin !is FirDeclarationOrigin.Source) {
        return
      }
      val symbol = declaration.symbol as? FirRegularClassSymbol ?: return
      val keyParameters = symbol.keyParameters(context.session)
      if (keyParameters == KeyParameters.NONE) {
        return
      }
      val constructor = declaration.primaryConstructorIfAny(context.session) ?: return
      for (parameter in constructor.valueParameterSymbols) {
        if (!keyParameters.includes(parameter.name) || !parameter.hasDefaultValue) {
          continue
        }
        val defaultValue = parameter.resolvedDefaultValue ?: continue
        val source = defaultValue.source ?: parameter.source ?: continue
        ArrayClassKeyValues(context.session, source) { reportSource, literal ->
            reportArrayClassKey(reportSource, literal)
          }
          .check(defaultValue)
      }
    }
  }
}

private fun FirAnnotation.isKeyAnnotation(session: FirSession): Boolean {
  val annotationClass =
    toAnnotationClassLikeSymbol(session) as? FirRegularClassSymbol ?: return false
  return annotationClass.isKeyAnnotation(session)
}

private fun FirRegularClassSymbol.isKeyAnnotation(session: FirSession): Boolean {
  val classIds = session.metroFirBuiltIns.classIds
  return isAnnotatedWithAny(session, classIds.qualifierAnnotations) ||
    isAnnotatedWithAny(session, classIds.mapKeyAnnotations) ||
    isAnnotatedWithAny(session, classIds.scopeAnnotations)
}

/**
 * Uses and declarations select the same key fields before reading arguments or defaults. Graph and
 * contribution schemas restrict matching to scope fields.
 */
private enum class KeyParameters {
  ALL,
  SCOPE,
  GRAPH_SCOPES,
  HILT_COMPONENTS,
  NONE;

  fun includes(name: Name): Boolean =
    when (this) {
      ALL -> true
      SCOPE -> name == Symbols.Names.scope
      GRAPH_SCOPES -> name == Symbols.Names.scope || name == Symbols.Names.additionalScopes
      HILT_COMPONENTS -> name == StandardNames.DEFAULT_VALUE_PARAMETER
      NONE -> false
    }
}

private fun FirRegularClassSymbol.keyParameters(session: FirSession): KeyParameters {
  if (isKeyAnnotation(session)) {
    return KeyParameters.ALL
  }
  val builtIns = session.metroFirBuiltIns
  return when {
    classId in builtIns.classIds.graphLikeAnnotations -> KeyParameters.GRAPH_SCOPES
    classId in builtIns.classIds.allContributesAnnotations -> KeyParameters.SCOPE
    builtIns.options.enableHiltInterop && classId == HiltSymbols.InstallIn ->
      KeyParameters.HILT_COMPONENTS
    else -> KeyParameters.NONE
  }
}

/** Each walk owns its cycle guard and report sites; defaults never leave the current source use. */
private class ArrayClassKeyValues(
  private val session: FirSession,
  private val rootSource: KtSourceElement,
  private val report: (KtSourceElement, FirGetClassCall) -> Unit,
) {
  private val visitedDefaults = mutableSetOf<FirValueParameterSymbol>()
  private val reportedSources = mutableSetOf<KtSourceElement>()

  fun check(expression: FirExpression, defaultSource: KtSourceElement? = null) {
    when (expression) {
      is FirGetClassCall -> {
        if (expression.resolveClassId(session) == StandardClassIds.Array) {
          val source = defaultSource ?: expression.source ?: rootSource
          if (reportedSources.add(source)) {
            report(source, expression)
          }
        }
      }
      is FirNamedArgumentExpression -> check(expression.expression, defaultSource)
      is FirSpreadArgumentExpression -> check(expression.expression, defaultSource)
      is FirAnnotation -> checkAnnotation(expression, defaultSource)
      is FirVarargArgumentsExpression -> {
        expression.arguments.forEach { check(it, defaultSource) }
      }
      is FirFunctionCall -> {
        expression.arguments.forEach { check(it, defaultSource) }
        val constructor =
          expression.calleeReference.toResolvedCallableSymbol() as? FirConstructorSymbol ?: return
        val annotationClass = constructor.resolvedReturnType.toRegularClassSymbol(session) ?: return
        if (annotationClass.classKind == ClassKind.ANNOTATION_CLASS) {
          val resolvedArguments = expression.resolvedArgumentMapping
          val suppliedNames =
            if (resolvedArguments != null) {
              resolvedArguments.values.mapTo(mutableSetOf()) { it.name }
            } else {
              expression.arguments.mapIndexedTo(mutableSetOf()) { index, argument ->
                (argument as? FirNamedArgumentExpression)?.name
                  ?: constructor.valueParameterSymbols.getOrNull(index)?.name
              }
            }
          for (parameter in constructor.valueParameterSymbols) {
            if (parameter.name !in suppliedNames) {
              checkDefault(parameter, annotationClass)
            }
          }
        }
      }
      is FirCall -> {
        // Covers array/collection literals across supported kotlinc versions.
        expression.arguments.forEach { check(it, defaultSource) }
      }
    }
  }

  fun checkAnnotation(
    annotation: FirAnnotation,
    defaultSource: KtSourceElement? = null,
    keyParameters: KeyParameters = KeyParameters.ALL,
  ) {
    val annotationClass =
      annotation.toAnnotationClassLikeSymbol(session) as? FirRegularClassSymbol ?: return
    val constructor = annotationClass.primaryConstructorIfAny(session) ?: return
    for ((index, parameter) in constructor.valueParameterSymbols.withIndex()) {
      if (!keyParameters.includes(parameter.name)) {
        continue
      }
      val argument = annotation.argumentAsOrNull<FirExpression>(session, parameter.name, index)
      if (argument != null) {
        check(argument, defaultSource)
      } else {
        checkDefault(parameter, annotationClass)
      }
    }
  }

  private fun checkDefault(
    parameter: FirValueParameterSymbol,
    annotationClass: FirRegularClassSymbol,
  ) {
    if (!parameter.hasDefaultValue || parameter.source == null) {
      return
    }
    // The declaration checker owns exactly these source defaults, including unused declarations.
    val hasDeclarationWarning =
      annotationClass.origin is FirDeclarationOrigin.Source &&
        annotationClass.keyParameters(session).includes(parameter.name)
    if (hasDeclarationWarning) {
      return
    }
    // Shared defaults report at the same root site. Visit each once to bound repeated DAG paths.
    if (!visitedDefaults.add(parameter)) {
      return
    }
    parameter.resolvedDefaultValue?.let { check(it, rootSource) }
  }
}

context(context: CheckerContext, reporter: DiagnosticReporter)
private fun reportArrayClassKey(source: KtSourceElement, literal: FirGetClassCall) {
  val literalType = literal.coneTypeIfResolved()
  val arrayName = literalType?.render(short = true)
  val componentName = literalType?.arrayMetadataComponentName(context.session)
  val description =
    when {
      arrayName == null -> "kotlinc can confuse this array class literal"
      componentName == null -> "kotlinc can confuse `$arrayName::class` with other class literals"
      else -> "kotlinc can read `$arrayName::class` as `$componentName::class`"
    }
  reporter.reportOn(
    source,
    KNOWN_KOTLINC_BUG_WARNING,
    "$description when reading annotations from another module. " +
      "This can make distinct qualifier values, map keys, or scopes match. " +
      "Use a dedicated non-array key class.",
  )
}

/**
 * Finds the component class stored by Kotlin's metadata writer. Unresolved elements return null.
 */
private fun ConeKotlinType.arrayMetadataComponentName(session: FirSession): String? {
  var componentType = fullyExpandedType(session)
  if (componentType.classId != StandardClassIds.Array) {
    return null
  }
  // The affected reader drops the array count and keeps this component class ID.
  while (!componentType.isPrimitiveArray) {
    val elementType = componentType.arrayElementType() ?: break
    componentType = elementType.fullyExpandedType(session)
  }
  val classId = componentType.classId ?: return null
  if (classId == StandardClassIds.Array) {
    return null
  }
  return classId.shortClassName.asString()
}
