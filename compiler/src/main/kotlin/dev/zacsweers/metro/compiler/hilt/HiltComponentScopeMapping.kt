// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.hilt

import java.util.Optional
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension.TypeResolveService
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.name.ClassId

/**
 * Maps Hilt component class IDs to the Metro scope class ID Metro should treat as their scope.
 *
 * For the 8 standard Android Hilt components the mapping is built in. For user-declared
 * `@DefineComponent` interfaces the scope is discovered on demand by looking up the component class
 * and finding a sibling annotation meta-annotated with `@javax.inject.Scope`.
 *
 * Instances are session-bound and not shared across compilations; the cache is plain [mutableMapOf]
 * since the FIR/IR-side callers Metro currently exercises don't access a single mapping
 * concurrently.
 */
internal class HiltComponentScopeMapping(private val session: FirSession) {

  private val cache = mutableMapOf<ClassId, Optional<ClassId>>()
  private val installInComponentsByClassId = mutableMapOf<ClassId, List<ClassId>>()

  /** Returns the Metro scope ClassId for [componentClassId], or null if no mapping is known. */
  fun resolveScope(componentClassId: ClassId): ClassId? {
    cache[componentClassId]?.let {
      return it.orElse(null)
    }
    val resolved = BUILT_INS[componentClassId] ?: resolveDefineComponentScope(componentClassId)
    cache[componentClassId] = Optional.ofNullable(resolved)
    return resolved
  }

  /**
   * Reads `@InstallIn`'s components for [classSymbol], caching by classId. The supertype
   * generator's per-(container, scope) loop asks the same container K times for K graph scopes;
   * this cache turns those K calls into one annotation parse.
   */
  fun installInComponents(
    classSymbol: FirRegularClassSymbol,
    typeResolver: TypeResolveService?,
  ): List<ClassId> =
    installInComponentsByClassId.getOrPut(classSymbol.classId) {
      val rawAnnotations = @OptIn(SymbolInternals::class) classSymbol.fir.annotations
      val installIn =
        rawAnnotations.firstOrNull { it.toAnnotationClassIdSafe(session) == HiltSymbols.InstallIn }
          ?: return@getOrPut emptyList()
      installIn.installInComponents(session, typeResolver)
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
