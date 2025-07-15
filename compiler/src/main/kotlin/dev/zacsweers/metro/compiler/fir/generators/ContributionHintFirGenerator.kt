// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.constructType
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.resolvedArgumentTypeRef
import dev.zacsweers.metro.compiler.fir.scopeArgument
import dev.zacsweers.metro.compiler.joinSimpleNames
import dev.zacsweers.metro.compiler.mapNotNullToSet
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.plugin.createTopLevelFunction
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.SupertypeSupplier
import org.jetbrains.kotlin.fir.resolve.TypeResolutionConfiguration
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.typeResolver
import org.jetbrains.kotlin.fir.scopes.createImportingScopes
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.platform.jvm.isJvm

/**
 * Generates hint marker functions for non-JVM/Android platforms during FIR. This handles both
 * scoped `@Inject` classes and classes with contributing annotations.
 *
 * For JVM/Android platforms, hints are generated in IR to avoid breaking incremental compilation.
 * For other platforms (Native, JS, WASM) where there is no incremental compilation, we generate
 * hints in FIR to ensure they are available for metadata.
 */
internal class ContributionHintFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  private val platform = session.moduleData.platform

  // Only generate hints for non-JVM/Android platforms
  // TODO make this configurable
  private val shouldGenerateHints = !platform.isJvm()

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

  val scopeSession = ScopeSession() // extension-level property

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

        val file: FirFile =
          session.firProvider.getFirClassifierContainerFileIfAny(contributingClass) ?: continue
        val scopes = createImportingScopes(file, session, scopeSession)
        val configuration = TypeResolutionConfiguration(scopes, emptyList(), useSiteFile = file)

        val contributionScopes: Set<ClassId> =
          contributions.mapNotNullToSet { annotation ->
            annotation.scopeArgument()?.let { getClassCall ->
              val reference = getClassCall.resolvedArgumentTypeRef() ?: return@let null
              val result =
                session.typeResolver.resolveType(
                  typeRef = reference,
                  configuration = configuration,
                  areBareTypesAllowed = true,
                  isOperandOfIsOperator = false,
                  resolveDeprecations = false,
                  supertypeSupplier = SupertypeSupplier.Default,
                  expandTypeAliases = false,
                )
              result.type.classId ?: return@let null
            }
          }
        for (contributionScope in contributionScopes) {
          val hintName = contributionScope.joinSimpleNames().shortClassName
          callableIds
            .getOrPut(CallableId(Symbols.FqNames.metroHintsPackage, hintName), ::mutableSetOf)
            .add(contributingClass)
        }
      }

      if (
        session.metroFirBuiltIns.options.enableScopedInjectClassHints &&
          contributingClasses.isEmpty()
      ) {
        // TODO Class factory scope hints
      }
      callableIds
    }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    if (!shouldGenerateHints) return
    register(session.predicates.contributesAnnotationPredicate)
    register(session.predicates.injectAnnotationPredicate)
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelCallableIds(): Set<CallableId> {
    if (!shouldGenerateHints) return emptySet()
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
        createTopLevelFunction(
            Keys.ContributionHint,
            callableId,
            session.builtinTypes.unitType.coneType,
          ) {
            valueParameter(Symbols.Names.contributed, { contributingClass.constructType(it) })
          }
          .symbol
      }
  }
}
