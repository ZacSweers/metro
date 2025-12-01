// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.constructType
import dev.zacsweers.metro.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.metro.compiler.fir.memoizedAllSessionsSequence
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.resolvedArgumentTypeRef
import dev.zacsweers.metro.compiler.fir.scopeArgument
import dev.zacsweers.metro.compiler.ir.transformers.HintGenerator
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.scopeHintFunctionName
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Generates hint marker functions for during FIR. This handles both scoped `@Inject` classes and
 * classes with contributing annotations.
 */
internal class ContributionHintFirGenerator(session: FirSession, compatContext: CompatContext) :
  FirDeclarationGenerationExtension(session), CompatContext by compatContext {

  private fun contributedClassSymbols(): List<FirClassSymbol<*>> {
    val injectedClasses =
      session.predicateBasedProvider.getSymbolsByPredicate(
        session.predicates.injectAnnotationPredicate
      )
    val contributedClasses =
      session.predicateBasedProvider.getSymbolsByPredicate(
        session.predicates.contributesAnnotationPredicate
      )

    return (injectedClasses + contributedClasses).filterIsInstance<FirClassSymbol<*>>()
  }

  private val allSessions = session.memoizedAllSessionsSequence
  private val typeResolverFactory = MetroFirTypeResolver.Factory(session, allSessions)

  private val contributedClassesByScope:
    FirCache<Unit, Map<CallableId, Set<FirClassSymbol<*>>>, Unit> =
    session.firCachesFactory.createCache { _, _ ->
      val callableIds = mutableMapOf<CallableId, MutableSet<FirClassSymbol<*>>>()

      val contributingClasses = contributedClassSymbols()
      for (contributingClass in contributingClasses) {
        val contributions =
          contributingClass
            .annotationsIn(session, session.classIds.allContributesAnnotations)
            .toList()

        if (contributions.isEmpty()) continue

        val typeResolver = typeResolverFactory.create(contributingClass) ?: continue

        val contributionScopes: Set<ClassId> =
          contributions.mapNotNullToSet { annotation ->
            annotation.scopeArgument()?.let { getClassCall ->
              val reference = getClassCall.resolvedArgumentTypeRef() ?: return@let null
              typeResolver.resolveType(typeRef = reference).classId ?: return@let null
            }
          }
        for (contributionScope in contributionScopes) {
          val hintName = contributionScope.scopeHintFunctionName()
          callableIds
            .getOrPut(CallableId(Symbols.FqNames.metroHintsPackage, hintName), ::mutableSetOf)
            .add(contributingClass)
        }
      }

      callableIds
    }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(session.predicates.contributesAnnotationPredicate)
    register(session.predicates.injectAnnotationPredicate)
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelCallableIds(): Set<CallableId> {
    return contributedClassesByScope.getValue(Unit, Unit).keys
  }

  @OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class)
  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val contributionsToScope =
      contributedClassesByScope.getValue(Unit, Unit)[callableId] ?: return emptyList()
    return contributionsToScope
      .sortedBy { it.classId.asFqNameString() }
      .map { contributingClass ->
        val containingFileName =
          HintGenerator.hintFileName(contributingClass.classId, callableId.callableName)
        createTopLevelFunction(
            Keys.ContributionHint,
            callableId,
            session.builtinTypes.unitType.coneType,
            containingFileName = containingFileName,
          ) {
            valueParameter(Symbols.Names.contributed, { contributingClass.constructType(it) })
          }
          .apply { markAsDeprecatedHidden(session) }
          .symbol as FirNamedFunctionSymbol
      }
  }

  override fun hasPackage(packageFqName: FqName): Boolean {
    return if (packageFqName == Symbols.FqNames.metroHintsPackage) {
      true
    } else {
      super.hasPackage(packageFqName)
    }
  }
}
