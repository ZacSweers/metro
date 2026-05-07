// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.computeOutrankedBindings
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.caching
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isBindingContainer
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.originClassId
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.rankValue
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.fir.resolveDefaultBindingType
import dev.zacsweers.metro.compiler.fir.resolvedAdditionalScopesClassIds
import dev.zacsweers.metro.compiler.fir.resolvedBindingArgument
import dev.zacsweers.metro.compiler.fir.resolvedExcludedClassIds
import dev.zacsweers.metro.compiler.fir.resolvedReplacedClassIds
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import dev.zacsweers.metro.compiler.fir.scopeArgument
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.ir.IrRankedBindingProcessing
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.safePathString
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.TraceCategories
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.TreeMap
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.descriptors.isInterface
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.ResolveStateAccess
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.extensions.ExperimentalSupertypesGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.lookupTracker
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.moduleVisibilityChecker
import org.jetbrains.kotlin.fir.recordFqNameLookup
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.scopes.getSingleClassifier
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds

internal class ContributedInterfaceSupertypeGenerator(
  session: FirSession,
  compatContext: CompatContext,
  private val loadExternalContributionExtensions:
    (FirSession, MetroOptions, CompatContext) -> List<MetroContributionExtension>,
) : FirSupertypeGenerationExtension(session), CompatContext by compatContext {

  /** External contribution extensions loaded via ServiceLoader. */
  private val externalContributionExtensions: List<MetroContributionExtension> by lazy {
    val options = session.metroFirBuiltIns.options
    loadExternalContributionExtensions(session, options, this)
  }

  private val typeResolverFactory by lazy { MetroFirTypeResolver.Factory(session).caching() }

  private val inCompilationScopesToContributions:
    FirCache<ClassId, Map<ClassId, Boolean>, TypeResolveService> =
    session.firCachesFactory.createCache { scopeClassId, typeResolver ->
      session.trace(
        name = { "In-compilation contributions for $scopeClassId" },
        category = TraceCategories.FIR_SUPERTYPE,
      ) {
        // In a KMP compilation we want to capture _all_ sessions' symbols. For example, if we are
        // generating supertypes for a graph in jvmMain, we want to capture contributions declared
        // in commonMain.
        val allSessions =
          sequenceOf(session).plus(session.moduleData.allDependsOnDependencies.map { it.session })

        // Predicates can't see the generated `MetroContribution` classes, but we can access them
        // by first querying the top level @ContributeX-annotated source symbols and then checking
        // their declaration scopes
        val contributingClasses =
          allSessions
            .flatMap {
              it.predicateBasedProvider.getSymbolsByPredicate(
                session.predicates.contributesAnnotationPredicate
              )
            }
            .filterIsInstance<FirRegularClassSymbol>()
            .filterNot { it.visibility == Visibilities.Private }
            .toList()

        getScopedContributions(contributingClasses, scopeClassId, typeResolver)
      }
    }

  private val generatedScopesToContributions:
    FirCache<ClassId, Map<ClassId, Boolean>, TypeResolveService> =
    session.firCachesFactory.createCache { scopeClassId, typeResolver ->
      session.trace(
        name = { "Classpath contributions for $scopeClassId" },
        category = TraceCategories.FIR_SUPERTYPE,
      ) {
        val scopeHintFqName = Symbols.FqNames.scopeHint(scopeClassId)
        val functionsInPackage =
          session.symbolProvider.getTopLevelFunctionSymbols(
            scopeHintFqName.parent(),
            scopeHintFqName.shortName(),
          )

        val filteredFunctions = functionsInPackage.filter {
          when (it.visibility) {
            Visibilities.Internal -> {
              it.moduleData == session.moduleData ||
                @OptIn(SymbolInternals::class)
                session.moduleVisibilityChecker?.isInFriendModule(it.fir) == true
            }
            else -> true
          }
        }

        val contributingClasses = filteredFunctions.mapNotNull { contribution ->
          // This is the single value param
          contribution.valueParameterSymbols
            .single()
            .resolvedReturnType
            .toRegularClassSymbol(session)
        }

        session.metroFirBuiltIns.writeDiagnostic(
          "discovered-hints-fir",
          { "${scopeClassId.safePathString}.txt" },
        ) {
          val allFunctions =
            functionsInPackage
              .map { @OptIn(SymbolInternals::class) it.fir.render() }
              .sorted()
              .joinToString("\n")

          val filtered =
            filteredFunctions
              .map { @OptIn(SymbolInternals::class) it.fir.render() }
              .sorted()
              .joinToString("\n")

          val contributedIds =
            contributingClasses.map { it.classId.safePathString }.sorted().joinToString("\n")

          buildString {
            appendLine("== All functions")
            appendLine(allFunctions)
            appendLine()
            appendLine("== Filtered functions")
            appendLine(filtered)
            appendLine()
            appendLine("== Contributing classes")
            appendLine(contributedIds)
          }
        }

        getScopedContributions(contributingClasses, scopeClassId, typeResolver)
      }
    }

  /**
   * @param contributingClasses The classes annotated with some number of @ContributesX annotations.
   * @return A mapping of contributions to the given [scopeClassId] and boolean indicating if
   *   they're a binding container or not.
   */
  private fun getScopedContributions(
    contributingClasses: List<FirRegularClassSymbol>,
    scopeClassId: ClassId,
    typeResolver: TypeResolveService,
  ): Map<ClassId, Boolean> {
    return buildMap {
      for (originClass in contributingClasses) {
        if (originClass.isBindingContainer(session)) {
          val hasMatchingScope =
            originClass.annotationsIn(session, session.classIds.contributesToAnnotations).any {
              it.resolvedScopeClassId(session, typeResolver) == scopeClassId
            }
          put(originClass.classId, hasMatchingScope)
          continue
        }

        val classDeclarationContainer =
          originClass.declaredMemberScope(session, memberRequiredPhase = null)

        val contributionNames =
          classDeclarationContainer.getClassifierNames().filter {
            it.identifier.startsWith(Symbols.Names.MetroContributionNamePrefix.identifier)
          }

        for (nestedClassName in contributionNames) {
          val nestedClass = classDeclarationContainer.getSingleClassifier(nestedClassName)

          val scopeId =
            nestedClass
              ?.annotationsIn(session, setOf(Symbols.ClassIds.metroContribution))
              ?.single()
              ?.resolvedScopeClassId(session, typeResolver)
          if (scopeId == scopeClassId) {
            put(originClass.classId.createNestedClassId(nestedClassName), false)
          }
        }
      }
    }
  }

  private fun FirAnnotationContainer.graphAnnotation(): FirAnnotation? {
    return annotations
      .annotationsIn(session, session.classIds.dependencyGraphAnnotations)
      .firstOrNull()
  }

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    // Note: we check the annotation directly rather than using the predicate-based provider
    // because generated @DependencyGraph classes (from external FIR extensions) are not
    // visible to the predicate-based provider.
    val graphAnnotation = declaration.graphAnnotation() ?: return false

    // @MergeContributionsInIr opts the graph out of FIR-side merging entirely.
    if (declaration.isAnnotatedWithAny(session, setOf(Symbols.ClassIds.mergeContributionsInIr))) {
      return false
    }

    // Can't check the scope class ID here but we'll check in computeAdditionalSupertypes
    return graphAnnotation.scopeArgument(session) != null
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    with(session.predicates) {
      register(
        dependencyGraphPredicate,
        contributesAnnotationPredicate,
        graphExtensionFactoryPredicate,
        qualifiersPredicate,
        bindingContainerPredicate,
        originPredicate,
        mergeContributionsInIrPredicate,
      )
    }
    // Register predicates from external contribution extensions
    for (extension in externalContributionExtensions) {
      with(extension) { registerPredicates() }
    }
  }

  @ExperimentalSupertypesGenerationApi
  override fun computeAdditionalSupertypesForGeneratedNestedClass(
    klass: FirRegularClass,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    // For generated @DependencyGraph classes (from external FIR extensions), FIR calls this
    // method instead of computeAdditionalSupertypes. Delegate to the shared implementation.
    if (klass.isAnnotatedWithAny(session, setOf(Symbols.ClassIds.mergeContributionsInIr))) {
      return emptyList()
    }
    return computeContributionSupertypes(
      classLikeDeclaration = klass,
      typeResolver = typeResolver,
      existingSupertypeClassIds = emptySet(),
    )
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    return computeContributionSupertypes(
      classLikeDeclaration = classLikeDeclaration,
      typeResolver = typeResolver,
      existingSupertypeClassIds = resolvedSupertypes.mapNotNullToSet { it.coneType.classId },
    )
  }

  private fun computeContributionSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    typeResolver: TypeResolveService,
    existingSupertypeClassIds: Set<ClassId>,
  ): List<ConeKotlinType> {
    val graphAnnotation = classLikeDeclaration.graphAnnotation() ?: return emptyList()

    val scopes =
      buildSet {
          graphAnnotation.resolvedScopeClassId(session, typeResolver)?.let(::add)
          graphAnnotation.resolvedAdditionalScopesClassIds(session, typeResolver).let(::addAll)
        }
        .filterNotTo(mutableSetOf()) { it == StandardClassIds.Nothing }

    for (classId in scopes) {
      session.lookupTracker?.recordFqNameLookup(
        Symbols.FqNames.scopeHint(classId),
        classLikeDeclaration.source,
        // The class source is the closest we can get to the file source,
        // and the file path lookup is cached internally.
        classLikeDeclaration.source,
      )
    }

    // Cache lambdas trace themselves on miss; cache hits don't open spans.
    val contributionMappingsByClassId =
      mutableMapOf<ClassId, Boolean>().apply {
        for (scopeClassId in scopes) {
          val classPathContributions =
            generatedScopesToContributions.getValue(scopeClassId, typeResolver)
          val inCompilationContributions =
            inCompilationScopesToContributions.getValue(scopeClassId, typeResolver)
          for ((classId, isBindingContainer) in
            (inCompilationContributions + classPathContributions)) {
            put(classId, isBindingContainer)
          }
        }
      }

    val contributionClassLikes =
      contributionMappingsByClassId.keys.map { classId ->
        classId.constructClassLikeType(emptyArray())
      }

    // Stable sort
    val contributions =
      TreeMap<ClassId, ConeKotlinType>(compareBy(ClassId::asString)).apply {
        for (contribution in contributionClassLikes) {
          // This is always the `MetroContribution`, the contribution is its parent
          val classId = contribution.expectAs<ConeKotlinType>().classId?.parentClassId ?: continue
          put(classId, contribution)
        }
      }

    // Gather contributions from external extensions
    // These provide supertypes directly along with replacement/origin metadata
    val externalContributions = mutableListOf<MetroContributionExtension.Contribution>()
    for (scopeClassId in scopes) {
      for (extension in externalContributionExtensions) {
        externalContributions.addAll(extension.getContributions(scopeClassId, typeResolverFactory))
      }
    }

    // Track which external contributions replace which classes
    val externalReplacements = mutableMapOf<ClassId, MutableSet<ClassId>>()

    // Add external contributions to the contributions map
    for (externalContribution in externalContributions) {
      externalContribution.supertype.classId ?: continue

      // Use the origin as the key (similar to how native contributions use parentClassId)
      contributions[externalContribution.originClassId] = externalContribution.supertype

      // Track replacements from external contributions
      for (replacedClassId in externalContribution.replaces) {
        externalReplacements
          .getOrPut(replacedClassId) { mutableSetOf() }
          .add(externalContribution.originClassId)
      }
    }

    val excluded = graphAnnotation.resolvedExcludedClassIds(session, typeResolver)
    if (contributions.isEmpty() && excluded.isEmpty() && externalContributions.isEmpty()) {
      return emptyList()
    }

    fun removeContribution(classId: ClassId, unmatched: MutableSet<ClassId>) {
      val removed = contributions.remove(classId)
      if (removed == null) {
        unmatched += classId
      }
    }

    // Build a cache of origin class -> contribution classes mappings upfront
    // This maps from an origin class to all contributions that have @Origin pointing to it
    // TODO make this lazily computed?
    val originToContributions = mutableMapOf<ClassId, MutableSet<ClassId>>()

    session.trace({ "Build origin map" }, category = TraceCategories.FIR_SUPERTYPE) {
      // Check regular contributions (classes with nested `MetroContribution`)
      for ((parentClassId, _) in contributions) {
        val parentSymbol = parentClassId.toSymbol(session)?.expectAsOrNull<FirRegularClassSymbol>()
        if (parentSymbol != null) {
          val localTypeResolver = typeResolverFactory.create(parentSymbol) ?: continue

          parentSymbol.originClassId(session, localTypeResolver)?.let { originClassId ->
            originToContributions.getAndAdd(originClassId, parentClassId)
          }
        }
      }

      // Also check binding containers (e.g., @ContributesTo classes)
      for ((containerClassId, isBindingContainer) in contributionMappingsByClassId) {
        if (isBindingContainer) {
          val containerSymbol =
            containerClassId.toSymbol(session)?.expectAsOrNull<FirRegularClassSymbol>()
          if (containerSymbol != null) {
            val localTypeResolver = typeResolverFactory.create(containerSymbol) ?: continue

            containerSymbol.originClassId(session, localTypeResolver)?.let { originClassId ->
              originToContributions.getAndAdd(originClassId, containerClassId)
            }
          }
        }
      }

      // Add external contributions' origins to the mapping
      // External contributions provide their origin directly in the Contribution data class
      for (externalContribution in externalContributions) {
        // The origin maps to itself for external contributions (they are self-referential)
        originToContributions
          .getOrPut(externalContribution.originClassId) { mutableSetOf() }
          .add(externalContribution.originClassId)
      }
    }

    val unmatchedExclusions = mutableSetOf<ClassId>()

    if (excluded.isNotEmpty()) {
      session.trace({ "Process exclusions" }, category = TraceCategories.FIR_SUPERTYPE) {
        for (excludedClassId in excluded) {
          removeContribution(excludedClassId, unmatchedExclusions)

          // If the target is a binding container, remove it from our mappings
          contributionMappingsByClassId[excludedClassId]
            ?.takeIf { it }
            ?.let { contributionMappingsByClassId.remove(excludedClassId) }

          // Remove contributions that have @Origin annotation pointing to the excluded class
          originToContributions[excludedClassId]?.forEach { contributionId ->
            removeContribution(contributionId, unmatchedExclusions)
          }

          // If the target is `@GraphExtension`, also implicitly exclude its nested factory if
          // available
          // TODO this is finicky and the target class's annotations aren't resolved.
          //  Ideally we also && targetClass.isAnnotatedWithAny(session,
          //  session.classIds.graphExtensionAnnotations)
          val targetClass =
            excludedClassId.toSymbol(session)?.expectAsOrNull<FirRegularClassSymbol>()
          if (targetClass != null) {
            for (nestedClassName in
              targetClass.declaredMemberScope(session, null).getClassifierNames()) {
              val nestedClassId = excludedClassId.createNestedClassId(nestedClassName)
              if (nestedClassId in contributions) {
                nestedClassId.toSymbol(session)?.expectAsOrNull<FirRegularClassSymbol>()?.let {
                  if (
                    it.isAnnotatedWithAny(
                      session,
                      session.classIds.graphExtensionFactoryAnnotations,
                    )
                  ) {
                    // Exclude its factory class too
                    removeContribution(nestedClassId, unmatchedExclusions)
                  }
                }
              }
            }
          }
        }
      }
    }

    if (unmatchedExclusions.isNotEmpty()) {
      session.metroFirBuiltIns.writeDiagnostic(
        "merging-unmatched-exclusions-fir",
        { "${classLikeDeclaration.classId.safePathString}.txt" },
      ) {
        unmatchedExclusions.map { it.safePathString }.sorted().joinToString("\n")
      }
    }

    // Process replacements from native contributions
    val unmatchedReplacements = mutableSetOf<ClassId>()

    session.trace({ "Process replacements" }, category = TraceCategories.FIR_SUPERTYPE) {
      contributionClassLikes
        .mapNotNull {
          val symbol = it.toClassSymbol(session)
          // TODO remove expectAs in 2.3.20
          if (contributionMappingsByClassId[it.expectAs<ConeKotlinType>().classId] == true) {
            // It's a binding container, use as-is
            symbol
          } else {
            // It's a contribution, get its original parent
            symbol?.getContainingClassSymbol()
          }
        }
        .plus(
          // When generateContributionProviders is enabled, binding contributions are represented
          // by provider-holder binding containers that only carry @Origin. Scan the origin class
          // too so its original @ContributesBinding(replaces = ...) annotations still participate
          // in FIR merging, matching the IR fallback logic.
          contributionMappingsByClassId.toList().mapNotNull { (containerClassId, isBindingContainer)
            ->
            if (!isBindingContainer) return@mapNotNull null

            val containerSymbol =
              containerClassId.toSymbol(session)?.expectAsOrNull<FirRegularClassSymbol>()
                ?: return@mapNotNull null
            val localTypeResolver =
              typeResolverFactory.create(containerSymbol) ?: return@mapNotNull null
            val originClassId =
              containerSymbol.originClassId(session, localTypeResolver) ?: return@mapNotNull null
            val originClass =
              originClassId.toSymbol(session)?.expectAsOrNull<FirClassSymbol<*>>()
                ?: return@mapNotNull null
            originClass
          }
        )
        .flatMap { contributingType ->
          val localTypeResolver =
            typeResolverFactory.create(contributingType) ?: return@flatMap emptySequence()

          contributingType
            .annotationsIn(session, session.classIds.allContributesAnnotationsWithContainers)
            .filter { it.scopeArgument(session)?.resolveClassId(localTypeResolver) in scopes }
            .flatMap { annotation ->
              annotation.resolvedReplacedClassIds(session, localTypeResolver)
            }
        }
        .distinct()
        .forEach { replacedClassId ->
          removeContribution(replacedClassId, unmatchedReplacements)

          // Remove contributions that have @Origin annotation pointing to the replaced class
          originToContributions[replacedClassId]?.forEach { contributionId ->
            removeContribution(contributionId, unmatchedReplacements)
          }
        }

      // Process replacements from external contributions
      for ((replacedClassId, _) in externalReplacements) {
        removeContribution(replacedClassId, unmatchedReplacements)

        // Remove contributions that have @Origin annotation pointing to the replaced class
        originToContributions[replacedClassId]?.forEach { contributionId ->
          removeContribution(contributionId, unmatchedReplacements)
        }
      }
    }

    if (unmatchedReplacements.isNotEmpty()) {
      session.metroFirBuiltIns.writeDiagnostic(
        "merging-unmatched-replacements-fir",
        { "${classLikeDeclaration.classId.safePathString}.txt" },
      ) {
        unmatchedReplacements.map { it.safePathString }.sorted().joinToString("\n")
      }
    }

    if (session.metroFirBuiltIns.options.enableDaggerAnvilInterop) {
      session.trace({ "Process rank replacements" }, category = TraceCategories.FIR_SUPERTYPE) {
        val unmatchedRankReplacements = mutableSetOf<ClassId>()
        val pendingRankReplacements =
          processRankBasedReplacements(scopes, contributions, typeResolver)

        pendingRankReplacements.distinct().forEach { replacedClassId ->
          removeContribution(replacedClassId, unmatchedRankReplacements)
        }

        if (unmatchedRankReplacements.isNotEmpty()) {
          session.metroFirBuiltIns.writeDiagnostic(
            "merging-unmatched-rank-replacements-fir",
            { "${classLikeDeclaration.classId.safePathString}.txt" },
          ) {
            unmatchedRankReplacements.map { it.safePathString }.sorted().joinToString("\n")
          }
        }
      }
    }

    val declarationClassId = classLikeDeclaration.classId
    // Used to avoid promoting parents whose visibility is narrower than the graph. The generated
    // MetroContributionTo<Scope> intermediate is @Deprecated(HIDDEN) so it's invisible to
    // Kotlin's exposure check; MergedContributionChecker reports the Metro-specific error. But
    // promoting the parent directly would additionally trigger the builtin exposure diagnostic.
    // Effective visibility isn't resolved yet at supertype generation, so use raw visibility.
    val declarationVisibility = (classLikeDeclaration as? FirClass)?.visibility

    // Collect external contribution supertypes (they don't need the same filtering as native ones)
    val externalSupertypes =
      externalContributions
        .filter {
          it.originClassId in contributions
        } // Only include those not removed by exclusions/replacements
        .map { it.supertype }
        .toSet()

    return session.trace({ "Build supertypes" }, category = TraceCategories.FIR_SUPERTYPE) {
      contributions
        .flatMap { (parentClassId, metroContribution) ->
          // External contributions pass through directly
          if (metroContribution in externalSupertypes) {
            return@flatMap listOf(metroContribution)
          }
          // Filter out binding containers and self-references — they participate in replacements
          // but not in supertypes
          if (
            metroContribution.classId?.parentClassId?.parentClassId == declarationClassId ||
              contributionMappingsByClassId[metroContribution.classId] == true
          ) {
            return@flatMap emptyList()
          }

          // For @ContributesTo interfaces, also emit the parent contributing interface directly
          // alongside the generated MetroContributionTo<Scope> intermediate. Kotlin/Native's
          // Objective-C framework exporter hides the @Deprecated(HIDDEN) intermediates and does
          // not transitively hoist their non-hidden supertypes into the child class's
          // superprotocol list, so the parent has to be a direct supertype to appear in
          // Swift/ObjC framework headers. Graph extension factories are excluded — they're
          // contributed via @ContributesTo but aren't meant to be inherited by the graph.
          // https://github.com/ZacSweers/metro/issues/2185
          val parentSymbol =
            parentClassId.toSymbol(session)?.expectAsOrNull<FirRegularClassSymbol>()
              ?: return@flatMap listOf(metroContribution)

          val contributesToThisScope =
            parentSymbol.annotationsIn(session, session.classIds.contributesToAnnotations).any {
              it.resolvedScopeClassId(session, typeResolver) in scopes
            }
          if (!contributesToThisScope) return@flatMap listOf(metroContribution)

          val promoteParent =
            parentSymbol.classKind.isInterface &&
              parentClassId !in existingSupertypeClassIds &&
              !parentSymbol.isAnnotatedWithAny(
                session,
                session.classIds.graphExtensionFactoryAnnotations,
              ) &&
              (declarationVisibility == null ||
                !parentSymbol.exposesNarrowerVisibilityThan(declarationVisibility))

          if (promoteParent) {
            listOf(metroContribution, parentClassId.constructClassLikeType(emptyArray()))
          } else {
            listOf(metroContribution)
          }
        }
        // Deduplicate by classId. The same contribution type can appear under different keys
        // when discovered via both hint-based and external extension paths.
        .distinctBy { it.classId }
    }
  }

  /**
   * This provides `ContributesBinding.rank` interop for users migrating from Dagger-Anvil to make
   * the migration to Metro more feasible.
   *
   * @return The bindings which have been outranked and should not be included in the merged graph.
   */
  private fun processRankBasedReplacements(
    allScopes: Set<ClassId>,
    contributions: Map<ClassId, ConeKotlinType>,
    typeResolver: TypeResolveService,
  ): Set<ClassId> {
    val rankedBindings =
      contributions.values
        .filterIsInstance<ConeClassLikeType>()
        .mapNotNull { it.toClassSymbol(session)?.getContainingClassSymbol() }
        .flatMap { contributingType ->
          contributingType
            .annotationsIn(session, session.classIds.contributesBindingAnnotationsWithContainers)
            .mapNotNull { annotation ->
              val scope =
                annotation.resolvedScopeClassId(session, typeResolver) ?: return@mapNotNull null
              if (scope !in allScopes) return@mapNotNull null

              val explicitBindingMissingMetadata =
                annotation.argumentAsOrNull<FirAnnotation>(
                  session,
                  Symbols.Names.binding,
                  index = 1,
                )

              if (explicitBindingMissingMetadata != null) {
                // This is a case where an explicit binding is specified but we receive the argument
                // as FirAnnotationImpl without the metadata containing the type arguments so we
                // short-circuit since we lack the info to compare it against other bindings.
                null
              } else {
                val boundType =
                  annotation.resolvedBindingArgument(session, typeResolver)?.let { explicitBinding
                    ->
                    if (explicitBinding is FirUserTypeRef) {
                        typeResolver.resolveUserType(explicitBinding)
                      } else {
                        explicitBinding
                      }
                      .coneType
                  } ?: contributingType.implicitBoundType(typeResolver) ?: return@mapNotNull null

                IrRankedBindingProcessing.ContributedBinding(
                  contributingType = contributingType,
                  typeKey =
                    FirTypeKey(
                      boundType,
                      contributingType.qualifierAnnotation(session, typeResolver),
                    ),
                  rank = annotation.rankValue(session),
                )
              }
            }
        }

    return computeOutrankedBindings(
      rankedBindings,
      typeKeySelector = { it.typeKey },
      rankSelector = { it.rank },
      classId = { it.contributingType.classId },
    )
  }

  @OptIn(ResolveStateAccess::class, SymbolInternals::class)
  private fun FirClassLikeSymbol<*>.implicitBoundType(
    typeResolver: TypeResolveService
  ): ConeKotlinType? {
    val supertypes =
      if (fir.resolveState.resolvePhase == FirResolvePhase.RAW_FIR) {
        // When processing bindings in the same module or compilation, we need to handle supertypes
        // that have not been resolved yet
        (this as FirClassSymbol<*>).fir.superTypeRefs.map { superTypeRef ->
          if (superTypeRef is FirUserTypeRef) {
              typeResolver.resolveUserType(superTypeRef)
            } else {
              superTypeRef
            }
            .coneType
        }
      } else {
        (this as FirClassSymbol<*>).resolvedSuperTypes
      }

    return resolveDefaultBindingType(session) ?: supertypes.singleOrNull()
  }

  /**
   * Walks this symbol and its containing-class chain and returns the narrowest raw visibility. Used
   * as a coarse stand-in for `effectiveVisibility`, which is not yet resolved during supertype
   * generation.
   */
  private fun FirClassLikeSymbol<*>.narrowestContainerVisibility(): Visibility {
    var narrowest: Visibility = visibility
    var current: FirClassLikeSymbol<*>? = getContainingClassSymbol()
    while (current != null) {
      val v = current.visibility
      if ((Visibilities.compare(v, narrowest) ?: 0) < 0) narrowest = v
      current = current.getContainingClassSymbol()
    }
    return narrowest
  }

  /**
   * Returns true if adding this symbol as a supertype of a declaration with [declarationVisibility]
   * would trigger Kotlin's builtin exposure diagnostic.
   */
  private fun FirClassLikeSymbol<*>.exposesNarrowerVisibilityThan(
    declarationVisibility: Visibility
  ): Boolean {
    return (Visibilities.compare(declarationVisibility, narrowestContainerVisibility()) ?: 0) > 0
  }
}
