// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.CREATE_DYNAMIC_GRAPH_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.CREATE_GRAPH_ERROR
import dev.zacsweers.metro.compiler.fir.bindingContainerErrorMessage
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isLocalClassOrAnonymousObject
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.nestedClasses
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.findClosestClassOrObject
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousObjectSymbol
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.toConeTypeProjection
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId

internal object CreateGraphChecker : FirFunctionCallChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(expression: FirFunctionCall) {
    val source = expression.source ?: return

    val callee = expression.toResolvedCallableSymbol() ?: return
    val session = context.session
    val builtins = session.metroFirBuiltIns

    val targetFunction = builtins.createGraphIntrinsicCallableIds[callee.callableId] ?: return

    // It's a createGraph() intrinsic
    val typeArg = expression.typeArguments.singleOrNull() ?: return
    val rawType =
      typeArg.toConeTypeProjection().type?.classLikeLookupTagIfAny?.toClassSymbol(session) ?: return

    // If it's factory-less
    if (targetFunction == builtins.createGraph || targetFunction == builtins.createDynamicGraph) {
      // Check that the target is a graph and has no factory
      if (
        !rawType.isAnnotatedWithAny(
          session,
          session.metroFirBuiltIns.classIds.dependencyGraphAnnotations,
        )
      ) {
        reporter.reportOn(
          typeArg.source ?: source,
          CREATE_GRAPH_ERROR,
          "`${targetFunction.name}` type argument '${rawType.classId.asFqNameString()}' must be annotated with a `@DependencyGraph` annotation.",
        )
      }

      // Check that it doesn't have a factory
      val creator =
        rawType.nestedClasses().find {
          it.isAnnotatedWithAny(
            session,
            session.metroFirBuiltIns.classIds.dependencyGraphFactoryAnnotations,
          )
        }
      if (creator != null) {
        reporter.reportOn(
          typeArg.source ?: source,
          CREATE_GRAPH_ERROR,
          "`${targetFunction.name}` type argument '${rawType.classId.asFqNameString()}' has a factory at '${creator.classId.asFqNameString()}'. Use `createGraphFactory` with that type instead.",
        )
      }
    } else {
      // It's a factory type
      if (
        !rawType.isAnnotatedWithAny(
          session,
          session.metroFirBuiltIns.classIds.dependencyGraphFactoryAnnotations,
        )
      ) {
        reporter.reportOn(
          typeArg.source ?: source,
          CREATE_GRAPH_ERROR,
          "`${targetFunction.name}` type argument '${rawType.classId.asFqNameString()}' must be annotated with a `@DependencyGraph.Factory` annotation.",
        )
      }
    }

    // If it's dynamic
    if (
      targetFunction == builtins.createDynamicGraph ||
        targetFunction == builtins.createDynamicGraphFactory
    ) {
      val containingClass = context.findClosestClassOrObject()
      val isInLocalClass = containingClass?.isLocalClassOrAnonymousObject == true
      if (isInLocalClass) {
        val message =
          if (containingClass is FirAnonymousObjectSymbol) {
            "This call is inside an anonymous object."
          } else {
            "Containing class '${containingClass.name}' is a local class."
          }
        reporter.reportOn(
          expression.source,
          CREATE_DYNAMIC_GRAPH_ERROR,
          "`${targetFunction.name}` can only be called from a top-level function or concrete class (non-anonymous, non-local). $message",
        )
      }

      if (expression.arguments.isEmpty()) {
        reporter.reportOn(
          typeArg.source ?: source,
          CREATE_DYNAMIC_GRAPH_ERROR,
          "`${targetFunction.name}` must have at least one argument.",
        )
        return
      }

      /**
       * Per the docs on [FirVarargArgumentsExpression]
       * > If a named argument is passed to a `vararg` parameter, [arguments] will contain a single
       * > [FirSpreadArgumentExpression] with [FirSpreadArgumentExpression.isNamed] set to `true`.
       */
      fun checkAndReportSpread(arg: FirExpression): Boolean {
        if (arg is FirSpreadArgumentExpression) {
          val message =
            if (arg.isNamed) {
              "`${targetFunction.name}` cannot have implicit spread arguments (i.e., passing one vararg to another via named argument)."
            } else {
              "`${targetFunction.name}` cannot have spread arguments."
            }
          reporter.reportOn(arg.source, CREATE_DYNAMIC_GRAPH_ERROR, message)
          return true
        }
        return false
      }

      // In theory, vararg means there's only ever one arg to a vararg
      val varargArg = expression.arguments.singleOrNull() ?: return

      // see doc on function
      checkAndReportSpread(varargArg)

      if (varargArg !is FirVarargArgumentsExpression) {
        return
      }

      val seenTypes = mutableMapOf<ClassId, FirExpression>()
      for (arg in varargArg.arguments) {
        if (checkAndReportSpread(arg)) continue

        val type = arg.resolvedType
        val classSymbol = type.toClassSymbol(session) ?: continue

        classSymbol.bindingContainerErrorMessage(session)?.let { bindingContainerErrorMessage ->
          reporter.reportOn(
            arg.source,
            CREATE_DYNAMIC_GRAPH_ERROR,
            "`${targetFunction.name}` only accepts concrete, non-local binding container types as arguments. $bindingContainerErrorMessage",
          )
          continue
        }

        val classId = classSymbol.classId
        val prev = seenTypes.put(classId, arg)
        if (prev != null) {
          listOf(prev, arg).forEach {
            reporter.reportOn(
              it.source,
              CREATE_DYNAMIC_GRAPH_ERROR,
              "`${targetFunction.name}` cannot have multiple arguments of the same (raw) type. '${classId.asFqNameString()}' is passed multiple times.",
            )
          }
        }
      }
    }
  }
}
