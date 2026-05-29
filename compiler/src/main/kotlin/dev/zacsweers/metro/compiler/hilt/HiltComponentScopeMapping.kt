// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.hilt

import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension.TypeResolveService
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.name.ClassId

/**
 * Maps Hilt component class IDs to the Metro scope class ID Metro should treat as their scope, and
 * owns the single-pass scan of in-round `@InstallIn` classes for its owning consumer.
 *
 * For the 8 standard Android Hilt components the mapping is built in. For user-declared
 * `@DefineComponent` interfaces the scope is discovered on demand by looking up the component class
 * and finding an annotation on the same interface whose annotation class is itself annotated with
 * `@Scope`.
 *
 * Each Hilt extension and the supertype generator constructs its own instance against the same
 * session; the scan inside an instance runs once.
 */
internal class HiltComponentScopeMapping(private val session: FirSession) {

  /**
   * Cache of successfully resolved scopes. We deliberately don't cache `null` so a transient
   * resolution failure (e.g. component annotations not yet promoted during an early FIR phase)
   * doesn't poison later, fully-resolved queries from the supertype generator or IR phase. The
   * built-in table covers the common case in a single lookup, so the retry cost for the rare
   * unresolved-component case is negligible.
   */
  private val cache = mutableMapOf<ClassId, ClassId>()

  /** Returns the Metro scope ClassId for [componentClassId], or null if no mapping is known. */
  fun resolveScope(componentClassId: ClassId): ClassId? {
    cache[componentClassId]?.let {
      return it
    }
    val resolved = BUILT_INS[componentClassId] ?: resolveDefineComponentScope(componentClassId)
    if (resolved != null) cache[componentClassId] = resolved
    return resolved
  }

  /** Single-pass in-round `@InstallIn` scan. Computed once per session. */
  val inRoundInstallIns: List<InRoundInstallIn> by memoize {
    scanInRoundInstallIns(MetroFirTypeResolver.Factory(session))
  }

  private val inRoundByClassId: Map<ClassId, InRoundInstallIn> by memoize {
    inRoundInstallIns.associateBy { it.classId }
  }

  /**
   * Reads `@InstallIn`'s components for [classSymbol]. In-round classes hit the single-pass scan;
   * upstream classpath classes fall through to a direct annotation read.
   */
  fun installInComponents(
    classSymbol: FirRegularClassSymbol,
    typeResolver: TypeResolveService?,
  ): List<ClassId> {
    inRoundByClassId[classSymbol.classId]?.let {
      return it.components
    }
    val rawAnnotations = @OptIn(SymbolInternals::class) classSymbol.fir.annotations
    val installIn =
      rawAnnotations.firstOrNull { it.toAnnotationClassIdSafe(session) == HiltSymbols.InstallIn }
        ?: return emptyList()
    return installIn.installInComponents(session, typeResolver)
  }

  private fun scanInRoundInstallIns(
    typeResolverFactory: MetroFirTypeResolver.Factory
  ): List<InRoundInstallIn> {
    val symbols =
      session.predicateBasedProvider.getSymbolsByPredicate(HiltSymbols.installInPredicate)
    if (symbols.isEmpty()) return emptyList()

    val result = mutableListOf<InRoundInstallIn>()
    for (symbol in symbols) {
      val classSymbol = symbol as? FirRegularClassSymbol ?: continue
      // Use raw `fir.annotations` rather than `resolvedCompilerAnnotationsWithClassIds`. The
      // latter is cached the first time it's accessed (e.g., by the predicate-based provider for
      // the predicate's annotation) and may not include the other annotations on the same class
      // (`@Module` / `@EntryPoint`) that got resolved later. The raw list contains every
      // annotation; `toAnnotationClassIdSafe` handles both resolved and unresolved forms.
      val rawAnnotations = @OptIn(SymbolInternals::class) classSymbol.fir.annotations
      val installInAnnotation =
        rawAnnotations.firstOrNull { it.toAnnotationClassIdSafe(session) == HiltSymbols.InstallIn }
          ?: continue
      val annoClassIds = rawAnnotations.mapNotNullToSet { it.toAnnotationClassIdSafe(session) }

      val typeResolver = typeResolverFactory.create(classSymbol)
      val components = installInAnnotation.installInComponents(session, typeResolver)
      if (components.isEmpty()) continue

      val isModule = HiltSymbols.Module in annoClassIds
      val isEntryPoint = HiltSymbols.EntryPoint in annoClassIds
      if (!isModule && !isEntryPoint) continue

      result += InRoundInstallIn(classSymbol.classId, components, isModule, isEntryPoint)
    }
    return result
  }

  private fun resolveDefineComponentScope(componentClassId: ClassId): ClassId? {
    val componentSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(componentClassId) as? FirRegularClassSymbol
        ?: return null

    for (annotation in componentSymbol.resolvedCompilerAnnotationsWithClassIds) {
      val annotationClassId = annotation.toAnnotationClassIdSafe(session) ?: continue
      if (annotationClassId == HiltSymbols.DefineComponent) continue
      val annotationSymbol =
        session.symbolProvider.getClassLikeSymbolByClassId(annotationClassId)
          as? FirRegularClassSymbol ?: continue
      val isScope =
        annotationSymbol.resolvedCompilerAnnotationsWithClassIds.any { metaAnnotation ->
          metaAnnotation.toAnnotationClassIdSafe(session) == HiltSymbols.JavaxScope
        }
      if (isScope) return annotationClassId
    }
    return null
  }

  companion object {
    /** The 8 standard Android Hilt components and their canonical scopes. */
    val BUILT_INS: Map<ClassId, ClassId> =
      mapOf(
        HiltSymbols.SingletonComponent to HiltSymbols.Singleton,
        HiltSymbols.ActivityRetainedComponent to HiltSymbols.ActivityRetainedScoped,
        HiltSymbols.ActivityComponent to HiltSymbols.ActivityScoped,
        HiltSymbols.ViewModelComponent to HiltSymbols.ViewModelScoped,
        HiltSymbols.FragmentComponent to HiltSymbols.FragmentScoped,
        HiltSymbols.ServiceComponent to HiltSymbols.ServiceScoped,
        HiltSymbols.ViewComponent to HiltSymbols.ViewScoped,
        HiltSymbols.ViewWithFragmentComponent to HiltSymbols.ViewScoped,
      )
  }
}
