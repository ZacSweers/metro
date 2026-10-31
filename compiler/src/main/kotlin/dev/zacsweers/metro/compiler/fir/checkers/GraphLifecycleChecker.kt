// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.generators.collectAbstractFunctions
import dev.zacsweers.metro.compiler.fir.implements
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.nestedClasses
import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.compiler.graph.LifecycleDiagnosticMessages
import dev.zacsweers.metro.compiler.graph.SuspendDiagnosticMessages
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.directOverriddenSymbolsSafe
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.dispatchReceiverClassLookupTagOrNull
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.unwrapFakeOverrides

/**
 * Checks each lifecycle callback declaration on its own.
 *
 * Contributed interfaces declare callbacks for graphs in other modules, so this runs on every
 * function and doesn't wait for a graph.
 */
internal object GraphLifecycleChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    if (declaration !is FirFunction || declaration.symbol !is FirNamedFunctionSymbol) {
      return
    }
    val session = context.session
    val phases = declaration.symbol.lifecyclePhases()

    // An override must repeat each lifecycle annotation of the function it overrides. Otherwise
    // the override would silently stop being a callback.
    if (declaration.isOverride) {
      for (overridden in declaration.symbol.directOverriddenSymbolsSafe()) {
        for (phase in overridden.lifecyclePhases()) {
          if (phase !in phases) {
            reporter.reportOn(
              declaration.source,
              MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
              LifecycleDiagnosticMessages.overrideMustRepeatAnnotation(
                phase.annotation.shortClassName.asString(),
              ),
            )
          }
        }
      }
    }
    if (phases.isEmpty()) {
      return
    }

    // The generated graph class calls each callback with only the graph as its receiver and the
    // injected values as its arguments. It can't see private members, supply other receivers, or
    // pick type arguments.
    val shapeError =
      when {
        declaration.dispatchReceiverType == null ->
          LifecycleDiagnosticMessages.CALLBACK_MUST_BE_MEMBER
        declaration.visibility == Visibilities.Private ->
          LifecycleDiagnosticMessages.CALLBACK_CANNOT_BE_PRIVATE
        declaration.receiverParameter != null ->
          LifecycleDiagnosticMessages.CALLBACK_CANNOT_HAVE_EXTENSION_RECEIVER
        declaration.symbol.contextParameterSymbols.isNotEmpty() ->
          LifecycleDiagnosticMessages.CALLBACK_CANNOT_HAVE_CONTEXT_PARAMETERS
        declaration.typeParameters.isNotEmpty() ->
          LifecycleDiagnosticMessages.CALLBACK_CANNOT_HAVE_TYPE_PARAMETERS
        declaration.body == null -> LifecycleDiagnosticMessages.CALLBACK_MUST_HAVE_BODY
        !declaration.symbol.resolvedReturnType.isUnit -> {
          LifecycleDiagnosticMessages.CALLBACK_MUST_RETURN_UNIT
        }
        else -> null
      }
    if (shapeError != null) {
      reporter.reportOn(declaration.source, MetroDiagnostics.DEPENDENCY_GRAPH_ERROR, shapeError)
    }

    val suspendProvidersDisabled = !session.metroFirBuiltIns.options.enableSuspendProviders
    if (declaration.isSuspend && suspendProvidersDisabled) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.SUSPEND_PROVIDERS_NOT_ENABLED,
        SuspendDiagnosticMessages.SUSPEND_PROVIDERS_NOT_ENABLED,
      )
    }
  }
}

/**
 * Checks the callbacks that FIR sees on [graph] against how the graph is closed and created.
 *
 * FIR merges contributions into `@DependencyGraph` classes, so [callables] includes contributed
 * callbacks there. Graph extensions merge contributions in IR, so IR repeats these checks for them.
 */
context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun checkGraphLifecycle(graph: FirClass, callables: List<FirCallableSymbol<*>>) {
  val session = context.session
  val callbacks =
    callables.filterIsInstance<FirNamedFunctionSymbol>().filter {
      it.isLifecycleCallback()
    }
  if (callbacks.isEmpty()) {
    return
  }
  val graphName = graph.classId.shortClassName.asString()
  val isSuspendCloseable = graph.symbol.implements(MetroClassIds.suspendCloseableGraph, session)
  val isCloseable =
    isSuspendCloseable || graph.symbol.implements(MetroClassIds.closeableGraph, session)
  if (!isCloseable) {
    reporter.reportOn(
      graph.source,
      MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
      LifecycleDiagnosticMessages.graphMustBeCloseable(graphName),
    )
    return
  }

  // GraphLifecycleChecker reports declared callbacks. An inherited one can come from a module with
  // suspend support, but this graph runs it.
  val inheritsSuspendCallback = callbacks.any { it.isSuspend && it.isDeclaredOutside(graph) }
  val suspendProvidersDisabled = !session.metroFirBuiltIns.options.enableSuspendProviders
  if (inheritsSuspendCallback && suspendProvidersDisabled) {
    reporter.reportOn(
      graph.source,
      MetroDiagnostics.SUSPEND_PROVIDERS_NOT_ENABLED,
      SuspendDiagnosticMessages.SUSPEND_PROVIDERS_NOT_ENABLED,
    )
  }

  val factoryFunction = graph.symbol.graphFactoryFunction()
  for (callback in callbacks) {
    if (!callback.isSuspend) {
      continue
    }
    val callbackName = callback.name.asString()
    // Inherited callbacks are declared in another class, so report them on the graph.
    val source =
      if (callback.isDeclaredOutside(graph)) {
        graph.source
      } else {
        callback.source
      }
    val phases = callback.lifecyclePhases()

    // Only SuspendCloseableGraph has a close() that can await a suspend callback.
    if (GraphLifecyclePhase.CLOSE in phases && !isSuspendCloseable) {
      reporter.reportOn(
        source,
        MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
        LifecycleDiagnosticMessages.suspendCloseNeedsSuspendGraph(graphName, callbackName),
      )
    }

    // Suspend init runs inside a suspend creation function, so the graph needs a suspend factory.
    // createGraph() and accessors that return the graph directly can't suspend.
    if (GraphLifecyclePhase.INIT !in phases) {
      continue
    }
    if (factoryFunction?.isSuspend != true) {
      reporter.reportOn(
        source,
        MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
        LifecycleDiagnosticMessages.suspendInitNeedsSuspendFactory(graphName, callbackName),
      )
    }
  }
}

/**
 * Checks that a [parent] whose `close()` doesn't suspend can close the [extension] it creates
 * through the callable at [source]. Callers only call this for `CloseableGraph` parents.
 */
context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun checkSyncParentCanClose(
  parent: FirClass,
  extension: FirClassSymbol<*>,
  source: KtSourceElement?,
) {
  // A parent closes its extensions from its own close(), which can't await a suspend one.
  if (!extension.implements(MetroClassIds.suspendCloseableGraph, context.session)) {
    return
  }
  reporter.reportOn(
    source,
    MetroDiagnostics.DEPENDENCY_GRAPH_ERROR,
    LifecycleDiagnosticMessages.syncParentCannotCloseSuspendChild(
      parent.classId.shortClassName.asString(),
      extension.classId.shortClassName.asString(),
    ),
  )
}

/** True when this callback is declared in a supertype of [graph] instead of the graph itself. */
private fun FirCallableSymbol<*>.isDeclaredOutside(graph: FirClass): Boolean {
  // Fake overrides from generic supertypes report the graph as their owner, so use the original.
  val declaringClass = unwrapFakeOverrides().dispatchReceiverClassLookupTagOrNull()?.classId
  return declaringClass != graph.classId
}

/** True for a `close()` that takes no arguments, which Metro implements on closeable graphs. */
@OptIn(ExperimentalContracts::class)
internal fun FirCallableSymbol<*>.hasCloseSignature(): Boolean {
  contract { returns(true) implies (this@hasCloseSignature is FirNamedFunctionSymbol) }
  if (this !is FirNamedFunctionSymbol || name.asString() != "close") {
    return false
  }
  return valueParameterSymbols.isEmpty() && receiverParameterSymbol == null
}

/** True when this function is annotated `@OnGraphInit` or `@OnGraphClose`. */
context(context: CheckerContext)
internal fun FirCallableSymbol<*>.isLifecycleCallback(): Boolean = lifecyclePhases().isNotEmpty()

/** The lifecycle phases this function is annotated for. Fake overrides use their original. */
context(context: CheckerContext)
private fun FirCallableSymbol<*>.lifecyclePhases(): Set<GraphLifecyclePhase> {
  val session = context.session
  return unwrapFakeOverrides()
    .annotationsIn(session, MetroClassIds.lifecycleCallbacks)
    .mapNotNullTo(mutableSetOf()) {
      GraphLifecyclePhase.forAnnotation(it.toAnnotationClassIdSafe(session))
    }
}

/** The creation function of this graph's nested factory, if it has one. */
context(context: CheckerContext)
private fun FirClassSymbol<*>.graphFactoryFunction(): FirNamedFunctionSymbol? {
  val session = context.session
  val factory =
    nestedClasses().firstOrNull {
      it.isAnnotatedWithAny(session, session.classIds.graphFactoryLikeAnnotations)
    } ?: return null
  return factory.collectAbstractFunctions(session)?.singleOrNull()
}
