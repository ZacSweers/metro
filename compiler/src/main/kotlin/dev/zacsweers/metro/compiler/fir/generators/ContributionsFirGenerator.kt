// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.MetroFirValueParameter
import dev.zacsweers.metro.compiler.fir.allSessions
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.anvilKClassBoundTypeArgument
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.buildClassReference
import dev.zacsweers.metro.compiler.fir.buildSimpleAnnotation
import dev.zacsweers.metro.compiler.fir.buildSimpleValueParameter
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.copyParameters
import dev.zacsweers.metro.compiler.fir.findInjectLikeConstructors
import dev.zacsweers.metro.compiler.fir.generateMemberFunction
import dev.zacsweers.metro.compiler.fir.hasOrigin
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isBindingContainer
import dev.zacsweers.metro.compiler.fir.isKiaIntoMultibinding
import dev.zacsweers.metro.compiler.fir.isResolved
import dev.zacsweers.metro.compiler.fir.mapKeyAnnotation
import dev.zacsweers.metro.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.fir.resolveDefaultBindingType
import dev.zacsweers.metro.compiler.fir.resolvedBindingArgument
import dev.zacsweers.metro.compiler.fir.resolvedClassId
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import dev.zacsweers.metro.compiler.fir.scopeAnnotations
import dev.zacsweers.metro.compiler.fir.scopeArgument
import dev.zacsweers.metro.compiler.joinSimpleNames
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.native.interop.parentsWithSelf
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.expressions.toReference
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.references.impl.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.ConstantValueKind

// TODO a bunch of this could probably be cleaned up now that the functions are generated in IR
/**
 * Generates `@MetroContribution`-annotated nested contribution classes for
 * `@Contributes*`-annotated classes.
 */
internal class ContributionsFirGenerator(session: FirSession, compatContext: CompatContext) :
  FirDeclarationGenerationExtension(session), CompatContext by compatContext {

  companion object {
    /** Suffix appended to the contributing class name for the holder class. */
    /** Prefix for synthetic scoped provider qualifier names. */
    private const val SCOPED_PROVIDER_PREFIX = "metro_scoped_"
  }

  private val generateContributionProviders: Boolean
    get() = session.metroFirBuiltIns.options.generateContributionProviders

  /** Lazily resolves binding contributions for a holder. */
  private fun ContributionsHolder.bindingContributions(): Set<Contribution.BindingContribution> {
    val contributions = findContributions(contributingClassSymbol) ?: return emptySet()
    return contributions.filterIsInstance<Contribution.BindingContribution>().toSet()
  }

  /** Whether a contributing class is scoped (has a scope annotation like @SingleIn). */
  private fun FirClassSymbol<*>.isScoped(): Boolean =
    resolvedCompilerAnnotationsWithClassIds.scopeAnnotations(session).any()

  /** Synthetic qualifier name for scoped instance sharing. */
  private fun syntheticScopedQualifierName(contributingClassId: ClassId): String =
    "$SCOPED_PROVIDER_PREFIX${contributingClassId.asString()}"

  /** Synthetic function name for scoped instance provider. */
  private fun syntheticScopedFunctionName(contributingClassSymbol: FirClassSymbol<*>): Name {
    val baseName =
      contributingClassSymbol.classId
        .joinSimpleNames(separator = "", camelCase = true)
        .shortClassName
        .asString()
    return "provideScopedInstance$baseName".asName()
  }

  /** Whether we need a synthetic scoped provider for this holder's contributions. */
  private fun needsSyntheticScopedProvider(holderInfo: ContributionsHolder): Boolean {
    return holderInfo.contributingClassSymbol.isScoped() &&
      holderInfo.bindingContributions().size > 1
  }

  private val allSessions = session.allSessions
  private val typeResolverFactory = MetroFirTypeResolver.Factory(session, allSessions)

  // Maps holder ClassId -> info. Only populated for generateContributionProviders mode.
  // This uses a simple map because the holder ClassId is deterministic (no scope resolution
  // needed).
  private val topLevelContributionHolders = mutableMapOf<ClassId, ContributionsHolder>()

  // For each contributing class, track its nested contribution classes and their scope arguments
  private val contributingClassToScopedContributions:
    FirCache<FirClassSymbol<*>, Map<Name, FirGetClassCall?>, Unit> =
    session.firCachesFactory.createCache { contributingClassSymbol, _ ->
      val contributionAnnotations =
        contributingClassSymbol.resolvedCompilerAnnotationsWithClassIds
          .annotationsIn(session, session.classIds.allContributesAnnotations)
          .toList()

      val contributionNamesToScopeArgs = mutableMapOf<Name, FirGetClassCall?>()

      if (contributionAnnotations.isNotEmpty()) {
        // We create a contribution class for each scope being contributed to. E.g. if there are
        // contributions for AppScope and LibScope we'll create `MetroContributionToLibScope` and
        // `MetroContributionToAppScope`
        // It'll try to use the fully name if possible, but because we really just need these to be
        // disambiguated we can just safely fall back to the short name in the worst case
        contributionAnnotations
          .mapNotNull { it.scopeArgument(session) }
          .distinctBy { it.scopeName(session) }
          .forEach { scopeArgument ->
            val nestedContributionName =
              scopeArgument.resolvedClassId()?.let { scopeClassId ->
                MetroContributions.metroContributionName(scopeClassId)
              }
                ?: scopeArgument.scopeName(session)?.let { scopeName ->
                  MetroContributions.metroContributionNameFromSuffix(scopeName)
                }
                ?: reportCompilerBug(
                  "Could not get scope name for ${scopeArgument.render()} on class ${contributingClassSymbol.classId}"
                )

            contributionNamesToScopeArgs[nestedContributionName] = scopeArgument
          }
      }
      contributionNamesToScopeArgs
    }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(session.predicates.contributesAnnotationPredicate)
    register(session.predicates.bindingContainerPredicate)
    register(session.predicates.mapKeysPredicate)
  }

  /** Computes a deterministic holder ClassId from a contributing class. No scope resolution. */
  private fun holderClassId(contributingClassId: ClassId): ClassId =
    MetroContributions.holderClassId(contributingClassId)

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    if (!generateContributionProviders) return emptySet()

    // Query predicate symbols WITHOUT calling findContributions() to avoid triggering
    // FIR resolution that causes reentrancy. We generate holder classes for all
    // @Contributes*-annotated classes; binding contributions are resolved later in
    // getNestedClassifiersNames/generateFunctions when annotations are available.
    val contributingClasses =
      session.predicateBasedProvider
        .getSymbolsByPredicate(session.predicates.contributesAnnotationPredicate)
        .filterIsInstance<FirClassSymbol<*>>()

    for (contributingClass in contributingClasses) {
      val classId = holderClassId(contributingClass.classId)
      topLevelContributionHolders.computeIfAbsent(classId) {
        ContributionsHolder(
          contributingClassId = contributingClass.classId,
          contributingClassSymbol = contributingClass,
        )
      }
    }
    return topLevelContributionHolders.keys
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    if (classId !in topLevelContributionHolders) return null

    return createTopLevelClass(classId, Keys.ContributionProviderHolderDeclaration) {
        modality = Modality.ABSTRACT
      }
      .apply { markAsDeprecatedHidden(session) }
      .symbol
  }

  private fun findContributions(contributingSymbol: FirClassSymbol<*>): Set<Contribution>? {
    val contributesToAnnotations = session.classIds.contributesToAnnotations
    val contributesBindingAnnotations = session.classIds.contributesBindingAnnotations
    val contributesIntoSetAnnotations = session.classIds.contributesIntoSetAnnotations
    val contributesIntoMapAnnotations = session.classIds.contributesIntoMapAnnotations
    val contributions = mutableSetOf<Contribution>()
    for (annotation in
      contributingSymbol.resolvedCompilerAnnotationsWithClassIds.filter { it.isResolved }) {
      val annotationClassId = annotation.toAnnotationClassIdSafe(session) ?: continue
      when (annotationClassId) {
        in contributesToAnnotations -> {
          contributions += Contribution.ContributesTo(contributingSymbol.classId)
        }
        in contributesBindingAnnotations -> {
          contributions +=
            if (annotation.isKiaIntoMultibinding(session)) {
              Contribution.ContributesIntoSetBinding(contributingSymbol, annotation) {
                listOf(buildIntoSetAnnotation(), buildBindsAnnotation())
              }
            } else {
              Contribution.ContributesBinding(contributingSymbol, annotation) {
                listOf(buildBindsAnnotation())
              }
            }
        }
        in contributesIntoSetAnnotations -> {
          contributions +=
            Contribution.ContributesIntoSetBinding(contributingSymbol, annotation) {
              listOf(buildIntoSetAnnotation(), buildBindsAnnotation())
            }
        }
        in contributesIntoMapAnnotations -> {
          contributions +=
            Contribution.ContributesIntoMapBinding(contributingSymbol, annotation) {
              listOf(buildIntoMapAnnotation(), buildBindsAnnotation())
            }
        }
        in session.classIds.customContributesIntoSetAnnotations -> {
          contributions +=
            if (contributingSymbol.mapKeyAnnotation(session) != null) {
              Contribution.ContributesIntoMapBinding(contributingSymbol, annotation) {
                listOf(buildIntoMapAnnotation(), buildBindsAnnotation())
              }
            } else {
              Contribution.ContributesIntoSetBinding(contributingSymbol, annotation) {
                listOf(buildIntoSetAnnotation(), buildBindsAnnotation())
              }
            }
        }
      }
    }

    return if (contributions.isEmpty()) {
      null
    } else {
      contributions
    }
  }

  // TODO dedupe with BindingMirrorClassFirGenerator
  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    // Only generate constructor for the mirror class
    if (classSymbol.name == Symbols.Names.BindsMirrorClass) {
      return setOf(SpecialNames.INIT)
    }

    // Private constructor for holder classes
    if (classSymbol.classId in topLevelContributionHolders) {
      return setOf(SpecialNames.INIT)
    }

    // Generate provides function names for nested contribution interfaces inside holder classes
    if (
      generateContributionProviders && classSymbol.hasOrigin(Keys.MetroContributionClassDeclaration)
    ) {
      val holderClassId = classSymbol.classId.parentClassId ?: return emptySet()
      val holderInfo = topLevelContributionHolders[holderClassId] ?: return emptySet()
      return buildSet {
        add(SpecialNames.INIT)
        // Add synthetic scoped provider function if needed
        if (needsSyntheticScopedProvider(holderInfo)) {
          add(syntheticScopedFunctionName(holderInfo.contributingClassSymbol))
        }
        for (contribution in holderInfo.bindingContributions()) {
          add(providesFunctionName(contribution, holderInfo.contributingClassSymbol))
        }
      }
    }

    return emptySet()
  }

  // TODO dedupe with BindingMirrorClassFirGenerator
  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    if (context.owner.name == Symbols.Names.BindsMirrorClass) {
      return listOf(createDefaultPrivateConstructor(context.owner, Keys.Default).symbol)
    }

    // Private constructor for holder classes
    if (context.owner.classId in topLevelContributionHolders) {
      return listOf(createDefaultPrivateConstructor(context.owner, Keys.Default).symbol)
    }

    // Private constructor for nested contribution interfaces inside holder classes
    if (
      generateContributionProviders &&
        context.owner.hasOrigin(Keys.MetroContributionClassDeclaration) &&
        context.owner.classId.parentClassId in topLevelContributionHolders
    ) {
      return listOf(createDefaultPrivateConstructor(context.owner, Keys.Default).symbol)
    }

    return emptyList()
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    if (context == null) return emptyList()
    if (!generateContributionProviders) return emptyList()
    if (!context.owner.hasOrigin(Keys.MetroContributionClassDeclaration)) return emptyList()

    val holderClassId = context.owner.classId.parentClassId ?: return emptyList()
    val holderInfo = topLevelContributionHolders[holderClassId] ?: return emptyList()

    val contributingClassSymbol = holderInfo.contributingClassSymbol
    val useSyntheticScoped = needsSyntheticScopedProvider(holderInfo)

    // Check if this is the synthetic scoped provider function
    if (
      useSyntheticScoped &&
        callableId.callableName == syntheticScopedFunctionName(contributingClassSymbol)
    ) {
      return listOf(generateSyntheticScopedFunction(context, holderInfo, callableId))
    }

    val matchingContribution =
      holderInfo.bindingContributions().find {
        providesFunctionName(it, contributingClassSymbol) == callableId.callableName
      } ?: return emptyList()

    // Resolve bound type for the return type
    val boundType =
      resolveBoundType(contributingClassSymbol, matchingContribution)
        ?: contributingClassSymbol.defaultType()

    val function =
      if (useSyntheticScoped) {
        // Wrapper function: takes @Named("metro_scoped_...") Any? parameter, returns bound type
        val qualifierName = syntheticScopedQualifierName(contributingClassSymbol.classId)
        generateMemberFunction(
            owner = context.owner,
            returnTypeProvider = { boundType },
            callableId = callableId,
            modality = Modality.FINAL,
          ) {
            val param =
              buildSimpleValueParameter(
                  name = Symbols.Names.instance,
                  type = session.builtinTypes.nullableAnyType,
                  containingFunctionSymbol = this.symbol,
                )
                .apply {
                  replaceAnnotationsSafe(annotations + listOf(buildNamedAnnotation(qualifierName)))
                }
            valueParameters += param
          }
          .also { it.isContributionProviderWrapper = true }
      } else {
        // Direct provides function: constructor params, scope annotation
        val injectConstructor =
          contributingClassSymbol.findInjectLikeConstructors(session).firstOrNull()
        val constructorParams =
          injectConstructor?.constructor?.valueParameterSymbols.orEmpty().map {
            MetroFirValueParameter(session, it)
          }
        generateMemberFunction(
          owner = context.owner,
          returnTypeProvider = { boundType },
          callableId = callableId,
          modality = Modality.FINAL,
        ) {
          copyParameters(
            functionBuilder = this,
            sourceParameters = constructorParams,
            copyParameterDefaults = true,
          )
        }
      }

    // Add annotations
    val functionAnnotations = buildList {
      // @Provides
      add(buildProvidesAnnotation())
      // @IntoSet / @IntoMap if applicable
      when (matchingContribution) {
        is Contribution.ContributesIntoSetBinding -> add(buildIntoSetAnnotation())
        is Contribution.ContributesIntoMapBinding -> {
          add(buildIntoMapAnnotation())
          // Copy map key annotation from contributing class
          contributingClassSymbol.mapKeyAnnotation(session)?.fir?.let { add(it) }
        }
        is Contribution.ContributesBinding -> {}
      }
      // Scope annotation only on direct provides (not wrappers — the synthetic handles scoping)
      if (!useSyntheticScoped) {
        contributingClassSymbol.resolvedCompilerAnnotationsWithClassIds
          .scopeAnnotations(session)
          .firstOrNull()
          ?.fir
          ?.let { add(it) }
      }
      // Copy qualifier annotation from contributing class, unless ignoreQualifier is set
      val ignoreQualifier =
        matchingContribution.annotation
          .argumentAsOrNull<FirLiteralExpression>(session, Symbols.Names.ignoreQualifier, index = 4)
          ?.value as? Boolean ?: false
      if (!ignoreQualifier) {
        contributingClassSymbol.qualifierAnnotation(session)?.fir?.let { add(it) }
      }
    }
    function.replaceAnnotationsSafe(function.annotations + functionAnnotations)

    return listOf(function.symbol as FirNamedFunctionSymbol)
  }

  /** Generates the synthetic scoped provider function that returns `Any?`. */
  private fun generateSyntheticScopedFunction(
    context: MemberGenerationContext,
    holderInfo: ContributionsHolder,
    callableId: CallableId,
  ): FirNamedFunctionSymbol {
    val contributingClassSymbol = holderInfo.contributingClassSymbol
    val qualifierName = syntheticScopedQualifierName(contributingClassSymbol.classId)

    // Get the @Inject constructor parameters
    val injectConstructor =
      contributingClassSymbol.findInjectLikeConstructors(session).firstOrNull()

    val constructorParams =
      injectConstructor?.constructor?.valueParameterSymbols.orEmpty().map {
        MetroFirValueParameter(session, it)
      }

    val function =
      generateMemberFunction(
        owner = context.owner,
        returnTypeProvider = { session.builtinTypes.nullableAnyType.coneType },
        callableId = callableId,
        modality = Modality.FINAL,
      ) {
        copyParameters(
          functionBuilder = this,
          sourceParameters = constructorParams,
          copyParameterDefaults = true,
        )
      }

    // Add @Provides + scope + @Named qualifier
    val functionAnnotations = buildList {
      add(buildProvidesAnnotation())
      // Scope annotation goes on the synthetic function
      contributingClassSymbol.resolvedCompilerAnnotationsWithClassIds
        .scopeAnnotations(session)
        .firstOrNull()
        ?.fir
        ?.let { add(it) }
      // @Named qualifier for the synthetic binding
      add(buildNamedAnnotation(qualifierName))
    }
    function.replaceAnnotationsSafe(function.annotations + functionAnnotations)

    return function.symbol as FirNamedFunctionSymbol
  }

  /** Compute a unique function name for a binding contribution. */
  private fun providesFunctionName(
    contribution: Contribution.BindingContribution,
    contributingClassSymbol: FirClassSymbol<*>,
  ): Name {
    val baseName =
      contributingClassSymbol.classId
        .joinSimpleNames(separator = "", camelCase = true)
        .shortClassName
        .asString()

    // Resolve the bound type to disambiguate multiple bindings from the same class
    val boundType = resolveBoundType(contributingClassSymbol, contribution)
    val boundSuffix =
      if (boundType != null) {
        val boundClassId = boundType.toRegularClassSymbol(session)?.classId
        val boundName =
          boundClassId
            ?.joinSimpleNames(separator = "", camelCase = true)
            ?.shortClassName
            ?.asString() ?: ""
        val nullableSuffix = if (boundType.isMarkedNullable) "Nullable" else ""
        "As$nullableSuffix$boundName"
      } else {
        ""
      }

    // Include contribution type prefix to disambiguate binding vs intoSet vs intoMap
    val prefix =
      when (contribution) {
        is Contribution.ContributesBinding -> "provide"
        is Contribution.ContributesIntoSetBinding -> "provideIntoSet"
        is Contribution.ContributesIntoMapBinding -> "provideIntoMap"
      }

    return "$prefix$baseName$boundSuffix".asName()
  }

  /** Resolve the bound type for a binding contribution. */
  private fun resolveBoundType(
    contributingClassSymbol: FirClassSymbol<*>,
    contribution: Contribution.BindingContribution,
  ): ConeKotlinType? {
    // Try explicit binding argument (Metro's binding() API and Anvil's boundType KClass)
    contribution.annotation.resolvedBindingArgument(session, typeResolver = null)?.let {
      return it.coneType
    }
    // Also try Anvil's boundType directly via resolvedClassId for cases where
    // typeResolver = null can't resolve the KClass argument
    contribution.annotation.anvilKClassBoundTypeArgument(session)?.let {
      return it.coneType
    }

    // Collect non-Any supertypes
    val supertypes =
      contributingClassSymbol.resolvedSuperTypeRefs.mapNotNull {
        it.coneType.takeIf { type ->
          type.toRegularClassSymbol(session)?.classId != StandardClassIds.Any
        }
      }

    // Check supertypes for @DefaultBinding — matches IR's resolveDefaultBinding behavior
    for (supertype in supertypes) {
      val supertypeSymbol = supertype.toRegularClassSymbol(session) ?: continue
      supertypeSymbol.resolveDefaultBindingType(session)?.let {
        return it
      }
    }

    // Fall back to single non-Any supertype
    return supertypes.singleOrNull()
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    if (context.owner.hasOrigin(Keys.MetroContributionClassDeclaration)) {
      // Contribution provider objects inside holder classes don't need a binding mirror
      val parentClassId = classSymbol.classId.parentClassId
      if (parentClassId != null && parentClassId in topLevelContributionHolders) {
        return emptySet()
      }

      // Metro contribution class that needs a binding mirror IFF it's not a @ContributesTo
      val isContributesTo =
        context.owner
          .parentsWithSelf(session)
          .drop(1)
          .firstOrNull { it is FirClassSymbol }
          ?.isAnnotatedWithAny(session, session.classIds.contributesToAnnotations) ?: false
      return if (!isContributesTo) {
        setOf(Symbols.Names.BindsMirrorClass)
      } else {
        emptySet()
      }
    }

    // Holder class: generate nested binding container objects per scope
    val contributionHolder = topLevelContributionHolders[classSymbol.classId]

    if (contributionHolder != null) {
      // Compute scope-dependent names using the short scope name which is available
      // even when the full scope ClassId hasn't been resolved yet.
      val contributingSymbol = contributionHolder.contributingClassSymbol
      val contributionAnnotations =
        contributingSymbol.resolvedCompilerAnnotationsWithClassIds
          .annotationsIn(session, session.classIds.allContributesAnnotations)
          .toList()

      val typeResolver = typeResolverFactory.create(contributingSymbol)
      return contributionAnnotations
        .mapNotNull { annotation ->
          if (typeResolver != null) {
            annotation.resolvedScopeClassId(session, typeResolver)
          } else {
            annotation.resolvedScopeClassId(session)
          }
        }
        .distinct()
        .mapToSet { scopeClassId -> Name.identifier("To${scopeClassId.shortClassName.asString()}") }
    }

    // Don't generate nested classes for binding container classes
    if (classSymbol.isBindingContainer(session)) {
      return emptySet()
    }

    if (generateContributionProviders) {
      // When generating contribution providers, only generate nested classes for ContributesTo
      // (binding contributions are generated as top-level holder classes instead)
      val contributions = findContributions(classSymbol)
      val hasContributesTo = contributions?.any { it is Contribution.ContributesTo } == true
      return if (hasContributesTo) {
        // Still need the nested contribution classes for ContributesTo
        contributingClassToScopedContributions.getValue(classSymbol, Unit).keys
      } else {
        emptySet()
      }
    }

    return contributingClassToScopedContributions.getValue(classSymbol, Unit).keys
  }

  /**
   * Assumes we are calling this on an annotation's 'scope' argument value -- used in contexts where
   * we can't resolve the scope argument to get the full classId
   */
  private fun FirGetClassCall?.scopeName(session: FirSession): String? {
    return this?.argument
      ?.toReference(session)
      ?.expectAsOrNull<FirSimpleNamedReference>()
      ?.name
      ?.identifier
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (name == Symbols.Names.BindsMirrorClass) {
      return createNestedClass(owner, name, Keys.BindingMirrorClassDeclaration) {
          modality = Modality.ABSTRACT
        }
        .apply { markAsDeprecatedHidden(session) }
        .symbol
    }

    // Generate nested contribution binding container objects inside holder classes
    if (owner.classId in topLevelContributionHolders) {
      val contributionHolder = topLevelContributionHolders[owner.classId]!!

      // Find the matching scope argument by scope short class name
      // Name is "To<ScopeShortName>", extract the scope name
      val expectedPrefix = "To"
      if (!name.asString().startsWith(expectedPrefix)) return null
      val scopeShortName = name.asString().removePrefix(expectedPrefix)

      val contributingSymbol = contributionHolder.contributingClassSymbol
      val contributionAnnotations =
        contributingSymbol.resolvedCompilerAnnotationsWithClassIds
          .annotationsIn(session, session.classIds.allContributesAnnotations)
          .toList()

      // Find the annotation whose resolved scope ClassId has the matching short name
      // Find all annotations for this scope to collect replaces from all of them
      val typeResolver = typeResolverFactory.create(contributingSymbol)
      val matchingAnnotations = contributionAnnotations.filter { annotation ->
        val scopeClassId =
          if (typeResolver != null) {
            annotation.resolvedScopeClassId(session, typeResolver)
          } else {
            annotation.resolvedScopeClassId(session)
          }
        scopeClassId?.shortClassName?.asString() == scopeShortName
      }
      val scopeArg = matchingAnnotations.firstNotNullOfOrNull { it.scopeArgument(session) }

      return createNestedClass(
          owner,
          name = name,
          key = Keys.MetroContributionClassDeclaration,
          classKind = ClassKind.OBJECT,
        ) {}
        .apply {
          markAsDeprecatedHidden(session)

          val allAnnotations = buildList {
            // @MetroContribution(scope) - for reference
            add(
              buildMetroContributionAnnotation().apply {
                replaceArgumentMapping(
                  buildAnnotationArgumentMapping {
                    scopeArg?.let { this.mapping[Symbols.Names.scope] = it }
                  }
                )
              }
            )
            // @Origin(<ContributingClass>::class)
            add(buildOriginAnnotation(contributionHolder.contributingClassId))
            // @BindingContainer
            add(buildBindingContainerAnnotation())
            // @IROnlyFactories — provider factories are generated in IR, not FIR
            add(buildIROnlyFactoriesAnnotation())
            // @ContributesTo(scope) — replaces are resolved from @Origin in IR via the
            // original contributing class's @ContributesBinding annotations
            add(buildContributesToAnnotation(scopeArg))
          }
          replaceAnnotations(annotations + allAnnotations)
        }
        .symbol
    }

    if (!name.identifier.startsWith(Symbols.StringNames.METRO_CONTRIBUTION_NAME_PREFIX)) return null
    val contributions = findContributions(owner) ?: return null
    return createNestedClass(
        owner,
        name = name,
        key = Keys.MetroContributionClassDeclaration,
        classKind = ClassKind.INTERFACE,
      ) {
        // annoyingly not implicit from the class kind
        modality = Modality.ABSTRACT
        for (contribution in contributions) {
          if (contribution is Contribution.ContributesTo) {
            superType(contribution.origin.defaultType(emptyList()))
          }
        }
      }
      .apply {
        markAsDeprecatedHidden(session)
        val metroContributionAnnotation =
          buildMetroContributionAnnotation().apply {
            replaceArgumentMapping(
              buildAnnotationArgumentMapping {
                val originalScopeArg =
                  contributingClassToScopedContributions.getValueIfComputed(owner)?.get(name)
                    ?: reportCompilerBug(
                      "Could not find a contribution scope for ${owner.classId}.$name"
                    )
                this.mapping[Symbols.Names.scope] = originalScopeArg
              }
            )
          }
        replaceAnnotations(annotations + listOf(metroContributionAnnotation))
      }
      .symbol
  }

  private fun buildBindsAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.bindsClassSymbol }
  }

  private fun buildIntoSetAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.intoSetClassSymbol }
  }

  private fun buildIntoMapAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.intoMapClassSymbol }
  }

  private fun buildProvidesAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.providesClassSymbol }
  }

  private fun buildMetroContributionAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.metroContributionClassSymbol }
  }

  private fun buildNamedAnnotation(name: String): FirAnnotation {
    val classId = ClassId(Symbols.FqNames.metroRuntimePackage, Name.identifier("Named"))
    val namedSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(classId) as FirRegularClassSymbol
    return buildSimpleAnnotation { namedSymbol }
      .apply {
        replaceArgumentMapping(
          buildAnnotationArgumentMapping {
            mapping[Name.identifier("name")] =
              buildLiteralExpression(
                source = null,
                kind = ConstantValueKind.String,
                value = name,
                setType = true,
              )
          }
        )
      }
  }

  private fun buildIROnlyFactoriesAnnotation(): FirAnnotation {
    return buildSimpleAnnotation {
      session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.irOnlyFactories)
        as FirRegularClassSymbol
    }
  }

  private fun buildBindingContainerAnnotation(): FirAnnotation {
    val classId = ClassId(Symbols.FqNames.metroRuntimePackage, Name.identifier("BindingContainer"))
    return buildSimpleAnnotation {
      session.symbolProvider.getClassLikeSymbolByClassId(classId) as FirRegularClassSymbol
    }
  }

  private fun buildContributesToAnnotation(scopeArg: FirGetClassCall?): FirAnnotation {
    val classId = ClassId(Symbols.FqNames.metroRuntimePackage, Name.identifier("ContributesTo"))
    return buildSimpleAnnotation {
        session.symbolProvider.getClassLikeSymbolByClassId(classId) as FirRegularClassSymbol
      }
      .apply {
        if (scopeArg != null) {
          replaceArgumentMapping(
            buildAnnotationArgumentMapping { this.mapping[Symbols.Names.scope] = scopeArg }
          )
        }
      }
  }

  private fun buildOriginAnnotation(originClassId: ClassId): FirAnnotation {
    val originAnnotationSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroOrigin)
        as FirRegularClassSymbol
    return buildSimpleAnnotation { originAnnotationSymbol }
      .apply {
        replaceArgumentMapping(
          buildAnnotationArgumentMapping {
            mapping[StandardNames.DEFAULT_VALUE_PARAMETER] =
              buildClassReference(session, originClassId)
          }
        )
      }
  }

  /**
   * Data class tracking a contribution provider holder class.
   *
   * @param contributingClassId The original @Inject class that has @ContributesBinding etc.
   * @param contributingClassSymbol The symbol for the contributing class.
   */
  private data class ContributionsHolder(
    val contributingClassId: ClassId,
    val contributingClassSymbol: FirClassSymbol<*>,
  )

  sealed interface Contribution {
    val origin: ClassId

    sealed interface BindingContribution : Contribution {
      // TODO make formatted name instead
      val callableName: String
      val annotatedType: FirClassSymbol<*>
      val annotation: FirAnnotation
      val buildAnnotations: () -> List<FirAnnotation>
    }

    data class ContributesTo(override val origin: ClassId) : Contribution

    data class ContributesBinding(
      override val annotatedType: FirClassSymbol<*>,
      override val annotation: FirAnnotation,
      override val buildAnnotations: () -> List<FirAnnotation>,
    ) : Contribution, BindingContribution {
      override val origin: ClassId = annotatedType.classId
      override val callableName: String = "binds"
    }

    data class ContributesIntoSetBinding(
      override val annotatedType: FirClassSymbol<*>,
      override val annotation: FirAnnotation,
      override val buildAnnotations: () -> List<FirAnnotation>,
    ) : Contribution, BindingContribution {
      override val origin: ClassId = annotatedType.classId
      override val callableName: String = "bindIntoSet"
    }

    data class ContributesIntoMapBinding(
      override val annotatedType: FirClassSymbol<*>,
      override val annotation: FirAnnotation,
      override val buildAnnotations: () -> List<FirAnnotation>,
    ) : Contribution, BindingContribution {
      override val origin: ClassId = annotatedType.classId
      override val callableName: String = "bindIntoMap"
    }
  }
}
