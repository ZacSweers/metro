// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.allAnnotations
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.directCallableSymbols
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isBindingContainer
import dev.zacsweers.metro.compiler.fir.isEffectivelyOpen
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.nestedClasses
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.symbols.DaggerSymbols
import dev.zacsweers.metro.compiler.tracing.trace
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.fullyExpandedClassId
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds

/** Recommends binding containers when a contribution has no public instance API to preserve. */
internal object ContributesToBindingContainerChecker : FirClassChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    declaration.source ?: return
    if (declaration.classKind != ClassKind.INTERFACE) return
    if (declaration.modality == Modality.SEALED) return
    val session = context.session
    if (
      !declaration.isAnnotatedWithAny(
        session,
        session.classIds.contributesToAnnotationsWithContainers,
      )
    ) {
      return
    }
    if (declaration.symbol.hasContainerOrGraphRole()) return

    session.trace(name = { "ContributesToBindingContainerChecker(${declaration.classId})" }) {
      context(session.compatContext) { checkCandidate(declaration) }
    }
  }

  /** Reports once when the interface's bindings can move to a contributed container. */
  context(context: CheckerContext, reporter: DiagnosticReporter, compatContext: CompatContext)
  private fun checkCandidate(declaration: FirClass) {
    val symbol = declaration.symbol
    var hasBinding = false
    for (callable in symbol.directCallableSymbols()) {
      val annotations = callable.metroAnnotations()
      if (annotations.isBindingDeclaration) {
        hasBinding = true
      } else if (callable.isEffectivelyOpen() || annotations.isOptionalBinding) {
        // Use the accessor checker's visibility rules to retain helpers and parameterized APIs too.
        return
      }
    }
    if (!hasBinding) {
      hasBinding = symbol.hasCompanionBindings()
    }
    if (!hasBinding || !symbol.hasSafeAncestors()) return

    reporter.reportOn(
      declaration.source,
      MetroDiagnostics.CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER,
    )
  }

  /**
   * Ancestor bindings are collected through graph supertypes and would be lost after conversion.
   */
  context(context: CheckerContext, compatContext: CompatContext)
  private fun FirClassSymbol<*>.hasSafeAncestors(): Boolean {
    val session = context.session
    val pending = ArrayDeque<ConeKotlinType>()
    pending.addAll(resolvedSuperTypes)
    val seen = mutableSetOf<ClassId>()
    while (pending.isNotEmpty()) {
      val classId = pending.removeFirst().fullyExpandedClassId(session) ?: return false
      // Inspect original declarations so authored Any overrides remain part of the public API.
      if (classId == StandardClassIds.Any) continue
      if (classId == this.classId) return false
      if (!seen.add(classId)) continue
      val ancestor =
        session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirClassSymbol<*>
          ?: return false
      if (ancestor.classKind != ClassKind.INTERFACE || ancestor.hasContainerOrGraphRole()) {
        return false
      }
      for (callable in ancestor.directCallableSymbols()) {
        if (callable.hasBindingAnnotations()) return false
        if (callable.isEffectivelyOpen()) return false
      }
      if (ancestor.hasCompanionBindings()) return false
      pending.addAll(ancestor.resolvedSuperTypes)
    }
    return true
  }

  /** Companion bindings belong to their enclosing container; other nested types are independent. */
  context(context: CheckerContext)
  private fun FirClassSymbol<*>.hasCompanionBindings(): Boolean {
    return nestedClasses().any { nested ->
      nested.isCompanion && nested.directCallableSymbols().any { it.hasBindingAnnotations() }
    }
  }

  /** Binary ancestors have plain FIR annotations, which metroAnnotations() currently skips. */
  context(context: CheckerContext)
  private fun FirCallableSymbol<*>.hasBindingAnnotations(): Boolean {
    val session = context.session
    val classIds = session.classIds
    return allAnnotations().any { annotation ->
      when (annotation.toAnnotationClassIdSafe(session)) {
        in classIds.providesAnnotations,
        in classIds.bindsAnnotations,
        in classIds.multibindsAnnotations -> true
        DaggerSymbols.ClassIds.DAGGER_BINDS_OPTIONAL_OF ->
          session.metroFirBuiltIns.options.enableDaggerRuntimeInterop
        else -> false
      }
    }
  }

  /** These roles already select container or graph behavior for the declaration. */
  context(context: CheckerContext)
  private fun FirClassSymbol<*>.hasContainerOrGraphRole(): Boolean {
    val session = context.session
    if (isBindingContainer(session)) return true
    val classIds = session.classIds
    return isAnnotatedWithAny(session, classIds.graphLikeAnnotations) ||
      isAnnotatedWithAny(session, classIds.graphFactoryLikeAnnotations)
  }

  /** Collection modifiers alone do not declare a binding. */
  private val MetroAnnotations<*>.isBindingDeclaration: Boolean
    get() = isProvides || isBinds || isMultibinds || isBindsOptionalOf
}
