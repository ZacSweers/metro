// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.FirRefTypeKey
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.MetroFirAnnotation
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.MetroFirValueParameter
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.anvilKClassBoundTypeArgument
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.buildClassReference
import dev.zacsweers.metro.compiler.fir.buildHiddenFromObjCAnnotation
import dev.zacsweers.metro.compiler.fir.buildSimpleAnnotation
import dev.zacsweers.metro.compiler.fir.buildSimpleValueParameter
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.copyParameters
import dev.zacsweers.metro.compiler.fir.findInjectLikeConstructors
import dev.zacsweers.metro.compiler.fir.generateMemberFunction
import dev.zacsweers.metro.compiler.fir.hasImplicitClassKey
import dev.zacsweers.metro.compiler.fir.hasOrigin
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isBindingContainer
import dev.zacsweers.metro.compiler.fir.isKiaIntoMultibinding
import dev.zacsweers.metro.compiler.fir.isResolved
import dev.zacsweers.metro.compiler.fir.mapKeyAnnotation
import dev.zacsweers.metro.compiler.fir.mapKeyClassValueExpression
import dev.zacsweers.metro.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.fir.resolveDefaultBindingTypeKey
import dev.zacsweers.metro.compiler.fir.resolvedBindingArgument
import dev.zacsweers.metro.compiler.fir.resolvedClassId
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import dev.zacsweers.metro.compiler.fir.scopeAnnotations
import dev.zacsweers.metro.compiler.fir.scopeArgument
import dev.zacsweers.metro.compiler.fir.usesContributionProviderPath
import dev.zacsweers.metro.compiler.joinSimpleNames
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.native.interop.parentsWithSelf
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.toAnnotationClass
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationCallCopy
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationCopy
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
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
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

  private val typeResolverFactory by lazy { MetroFirTypeResolver.Factory(session) }

  // Maps holder ClassId -> info. Only populated for generateContributionProviders mode.
  // This uses a simple map because the holder ClassId is deterministic (no scope resolution
  // needed).
  private val topLevelContributionHolders = mutableMapOf<ClassId, ContributionsHolder>()

  /** Cache for [resolveDefaultBindingTypeKey] results to avoid redundant resolution. */
  private val defaultBindingTypeKeyCache = mutableMapOf<ClassId, FirRefTypeKey?>()

  /** Returns the holder for [classId] if it uses the contribution provider path, else null. */
  private fun getHolder(classId: ClassId): ContributionsHolder? {
    val holder = topLevelContributionHolders[classId] ?: return null
    if (!holder.contributingClassSymbol.usesContributionProviderPath(session)) return null
    return holder
  }

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
    register(session.predicates.qualifiersPredicate)
    register(session.predicates.assistedFactoryAnnotationPredicate)
  }

  /** Computes a deterministic holder ClassId from a contributing class. No scope resolution. */
  private fun holderClassId(contributingClassId: ClassId): ClassId =
    MetroContributions.holderClassId(contributingClassId)

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    if (!generateContributionProviders) return emptySet()

    // Avoid annotation resolution here — in the IDE this runs during
    // FirExtensionDeclarationsSymbolProvider's cache init and re-entering it causes a
    // StackOverflowError. Filtering is handled in getHolder() at each usage site.
    val contributingClasses =
      session.predicateBasedProvider
        .getSymbolsByPredicate(session.predicates.contributesBindingLikeAnnotationsPredicate)
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
    if (getHolder(classId) == null) return null

    return createTopLevelClass(classId, Keys.ContributionProviderHolderDeclaration) {
        modality = Modality.ABSTRACT
      }
      .apply {
        buildHiddenFromObjCAnnotation(session)?.let { replaceAnnotationsSafe(listOf(it)) }
        markAsDeprecatedHidden(session)
      }
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
          val resolve = { resolveBindingAnnotations(contributingSymbol, annotation) }
          contributions +=
            if (annotation.isKiaIntoMultibinding(session)) {
              Contribution.ContributesIntoSetBinding(contributingSymbol, annotation, resolve) {
                listOf(buildIntoSetAnnotation(), buildBindsAnnotation())
              }
            } else {
              Contribution.ContributesBinding(contributingSymbol, annotation, resolve) {
                listOf(buildBindsAnnotation())
              }
            }
        }
        in contributesIntoSetAnnotations -> {
          contributions +=
            Contribution.ContributesIntoSetBinding(
              contributingSymbol,
              annotation,
              { resolveBindingAnnotations(contributingSymbol, annotation) },
            ) {
              listOf(buildIntoSetAnnotation(), buildBindsAnnotation())
            }
        }
        in contributesIntoMapAnnotations -> {
          contributions +=
            Contribution.ContributesIntoMapBinding(
              contributingSymbol,
              annotation,
              { resolveBindingAnnotations(contributingSymbol, annotation) },
            ) {
              listOf(buildIntoMapAnnotation(), buildBindsAnnotation())
            }
        }
        in session.classIds.customContributesIntoSetAnnotations -> {
          // For custom contributes annotations, map key is always on the class (not on a
          // binding type ref), so we can check it directly without full bound type resolution.
          val resolve = { resolveBindingAnnotations(contributingSymbol, annotation) }
          contributions +=
            if (contributingSymbol.mapKeyAnnotation(session) != null) {
              Contribution.ContributesIntoMapBinding(contributingSymbol, annotation, resolve) {
                listOf(buildIntoMapAnnotation(), buildBindsAnnotation())
              }
            } else {
              Contribution.ContributesIntoSetBinding(contributingSymbol, annotation, resolve) {
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
    if (getHolder(classSymbol.classId) != null) {
      return setOf(SpecialNames.INIT)
    }

    // Generate provides function names for nested contribution interfaces inside holder classes
    if (
      generateContributionProviders && classSymbol.hasOrigin(Keys.MetroContributionClassDeclaration)
    ) {
      val holderClassId = classSymbol.classId.parentClassId ?: return emptySet()
      val holderInfo = getHolder(holderClassId) ?: return emptySet()
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
    if (getHolder(context.owner.classId) != null) {
      return listOf(createDefaultPrivateConstructor(context.owner, Keys.Default).symbol)
    }

    // Private constructor for nested contribution interfaces inside holder classes
    if (
      generateContributionProviders &&
        context.owner.hasOrigin(Keys.MetroContributionClassDeclaration) &&
        getHolder(context.owner.classId.parentClassId ?: return emptyList()) != null
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
    val holderInfo = getHolder(holderClassId) ?: return emptyList()

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
      resolveBoundTypeRef(contributingClassSymbol, matchingContribution.annotation)
        .first
        ?.coneTypeOrNull ?: contributingClassSymbol.defaultType()

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
          ) { original ->
            // Force a resolved type ref. The source constructor's value parameters can still be at
            // ANNOTATION_ARGUMENTS under LL FIR, so copying their returnTypeRef directly would
            // leave a FirUserTypeRef on the generated parameter and crash later argument
            // resolution. Use the symbol's resolvedReturnTypeRef to preserve the declared type
            // (including Provider/Lazy wrappers) — typeKey.type strips those.
            this.returnTypeRef = original.symbol.resolvedReturnTypeRef
          }
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
          // Copy map key annotation (already resolved on the contribution)
          val mapKey = matchingContribution.mapKey
          mapKey?.fir?.expectAsOrNull<FirAnnotationCall>()?.let { mapKeyFirAnnotation ->
            // For implicit class keys (@MapKey(implicitClassKey = true)), the annotation
            // value is Nothing::class (sentinel) or absent. Build a new annotation with the
            // contributing class as the value instead of copying the sentinel.
            var added = false
            if (mapKey.hasImplicitClassKey(session)) {
              val valueExpr = mapKey.mapKeyClassValueExpression()
              val valueClassId = valueExpr?.resolvedClassId() ?: StandardClassIds.Nothing
              if (valueClassId == StandardClassIds.Nothing) {
                // It's the sentinel or omitted, so use the annotated class
                mapKeyFirAnnotation.toAnnotationClass(session)?.let { mapKeyClass ->
                  add(
                    buildSimpleAnnotation { mapKeyClass.symbol }
                      .apply {
                        replaceArgumentMapping(
                          buildAnnotationArgumentMapping {
                            mapping[StandardNames.DEFAULT_VALUE_PARAMETER] =
                              buildClassReference(session, contributingClassSymbol.classId)
                          }
                        )
                      }
                  )
                  added = true
                }
              } else {
                // Explicit value provided, just copy below
              }
            } else {
              // Regular map key, copy below
            }

            if (!added) {
              add(
                buildAnnotationCallCopy(mapKeyFirAnnotation) {
                  source =
                    mapKeyFirAnnotation.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
                  containingDeclarationSymbol = function.symbol
                }
              )
            }
          }
        }
        is Contribution.ContributesBinding -> {}
      }

      // Scope annotation only on direct provides (not wrappers — the synthetic handles scoping)
      if (!useSyntheticScoped) {
        contributingClassSymbol.resolvedCompilerAnnotationsWithClassIds
          .scopeAnnotations(session)
          .firstOrNull()
          ?.fir
          ?.expectAsOrNull<FirAnnotationCall>()
          ?.let {
            add(
              buildAnnotationCallCopy(it) {
                source = it.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
                containingDeclarationSymbol = function.symbol
              }
            )
          }
      }

      // Copy qualifier annotation from contributing class, unless ignoreQualifier is set
      val ignoreQualifier =
        matchingContribution.annotation
          .argumentAsOrNull<FirLiteralExpression>(session, Symbols.Names.ignoreQualifier, index = 4)
          ?.value as? Boolean ?: false

      if (!ignoreQualifier) {
        matchingContribution.qualifier?.fir?.expectAsOrNull<FirAnnotation>()?.let {
          val anno =
            if (it is FirAnnotationCall) {
              buildAnnotationCallCopy(it) {
                source = it.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
                containingDeclarationSymbol = function.symbol
              }
            } else {
              // External decl we're copying from
              buildAnnotationCopy(it) {
                source = it.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
              }
            }
          add(anno)
        }
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
        ?.expectAsOrNull<FirAnnotationCall>()
        ?.let {
          add(
            buildAnnotationCallCopy(it) {
              source = it.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
              containingDeclarationSymbol = function.symbol
            }
          )
        }
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
    val boundType =
      resolveBoundTypeRef(contributingClassSymbol, contribution.annotation).first?.coneTypeOrNull
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

    // Include qualifier and map key hashes to disambiguate multiple contributions with the same
    // bound type but different qualifiers or map keys (e.g., two @ContributesIntoSet with
    // different @Named qualifiers on the explicit binding type).
    val annotationSuffix = buildString {
      contribution.qualifier?.hashCode()?.toUInt()?.let(::append)
      contribution.mapKey?.hashCode()?.toUInt()?.let(::append)
    }

    return "$prefix$baseName$boundSuffix$annotationSuffix".asName()
  }

  /**
   * Resolves qualifier and map key annotations for a binding contribution, checking the explicit
   * binding type ref first and falling back to the contributing class declaration.
   */
  private fun resolveBindingAnnotations(
    contributingClassSymbol: FirClassSymbol<*>,
    annotation: FirAnnotation,
  ): Contribution.BindingAnnotations {
    val (boundTypeRef, defaultBindingQualifier) =
      resolveBoundTypeRef(contributingClassSymbol, annotation)
    val boundTypeAnnotations = boundTypeRef?.annotations
    val classAnnotations = contributingClassSymbol.resolvedCompilerAnnotationsWithClassIds
    return Contribution.BindingAnnotations(
      qualifier =
        boundTypeAnnotations?.qualifierAnnotation(session)
          ?: defaultBindingQualifier
          ?: classAnnotations.qualifierAnnotation(session),
      mapKey =
        boundTypeAnnotations?.mapKeyAnnotation(session)
          ?: classAnnotations.mapKeyAnnotation(session),
    )
  }

  /**
   * Resolve the bound type ref for a binding contribution. Returns a [FirTypeRef] so callers can
   * read both the type (via [FirTypeRef.coneType]) and any type annotations (qualifier, map key).
   * Also returns any qualifier from a `@DefaultBinding` resolution (which may store the qualifier
   * on the mirror function rather than the type ref).
   */
  private fun resolveBoundTypeRef(
    contributingClassSymbol: FirClassSymbol<*>,
    annotation: FirAnnotation,
  ): Pair<FirTypeRef?, MetroFirAnnotation?> {
    // Try explicit binding argument (Metro's binding() API and Anvil's boundType KClass)
    annotation.resolvedBindingArgument(session, typeResolver = null)?.let {
      return it to null
    }
    // Also try Anvil's boundType directly via resolvedClassId for cases where
    // typeResolver = null can't resolve the KClass argument (no type annotations possible here)
    annotation.anvilKClassBoundTypeArgument(session)?.let {
      return it to null
    }

    // Collect non-Any supertypes
    val supertypes =
      contributingClassSymbol.resolvedSuperTypeRefs.filter {
        it.coneType.toRegularClassSymbol(session)?.classId != StandardClassIds.Any
      }

    // Check supertypes for @DefaultBinding — matches IR's resolveDefaultBinding behavior
    for (superTypeRef in supertypes) {
      val supertypeSymbol = superTypeRef.coneType.toRegularClassSymbol(session) ?: continue
      val classId = supertypeSymbol.classId
      val result =
        defaultBindingTypeKeyCache.getOrPut(classId) {
          supertypeSymbol.resolveDefaultBindingTypeKey(session)
        }
      if (result != null) {
        return result.typeRef to result.qualifier
      }
    }

    // Fall back to single non-Any supertype
    return supertypes.singleOrNull() to null
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    if (context.owner.hasOrigin(Keys.MetroContributionClassDeclaration)) {
      // Contribution provider objects inside holder classes don't need a binding mirror
      val parentClassId = classSymbol.classId.parentClassId
      if (parentClassId != null && getHolder(parentClassId) != null) {
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
    val contributionHolder = getHolder(classSymbol.classId)

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

    if (classSymbol.usesContributionProviderPath(session)) {
      // When generating contribution providers, only generate nested classes for ContributesTo
      // (binding contributions are generated as top-level holder classes instead).
      // Classes that don't skip factory (e.g. extension-generated top-level classes,
      // @ExposeImplBinding) fall through to the standard nested contribution path.
      val contributions = findContributions(classSymbol)
      val hasContributesTo = contributions?.any { it is Contribution.ContributesTo } == true
      // Still need the nested contribution classes for ContributesTo
      return if (hasContributesTo) {
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
    val contributionHolder = getHolder(owner.classId)
    if (contributionHolder != null) {

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
            // @Origin(<ContributingClass>::class, context = "contribution_provider")
            add(
              buildOriginAnnotation(
                contributionHolder.contributingClassId,
                context = Symbols.StringNames.CONTRIBUTION_PROVIDER_ORIGIN_CONTEXT,
              )
            )
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
    val generateAsContainer =
      session.metroFirBuiltIns.options.bindingContributionsAsContainers &&
        contributions.none { it is Contribution.ContributesTo }
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
        val newAnnotations = buildList {
          add(
            buildMetroContributionAnnotation().apply {
              contributingClassToScopedContributions.getValueIfComputed(owner)?.get(name)?.let {
                originalScopeArg ->
                replaceArgumentMapping(
                  buildAnnotationArgumentMapping { mapping[Symbols.Names.scope] = originalScopeArg }
                )
              }
            }
          )
          // Newer binding contributions are routed as @BindingContainer instead of being merged
          // into the graph as a supertype, so that graphs don't accumulate one supertype per
          // contributing class.
          if (generateAsContainer) {
            add(buildBindingContainerAnnotation())
            add(buildOriginAnnotation(owner.classId))
            add(buildComptimeOnlyAnnotation())
          }
        }
        replaceAnnotations(annotations + newAnnotations)
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
    val classId = ClassId(Symbols.FqNames.metroRuntimePackage, "BindingContainer".asName())
    return buildSimpleAnnotation {
      session.symbolProvider.getClassLikeSymbolByClassId(classId) as FirRegularClassSymbol
    }
  }

  private fun buildComptimeOnlyAnnotation(): FirAnnotation {
    return buildSimpleAnnotation {
      session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.ComptimeOnly)
        as FirRegularClassSymbol
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

  private fun buildOriginAnnotation(
    originClassId: ClassId,
    context: String? = null,
  ): FirAnnotation {
    val originAnnotationSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroOrigin)
        as FirRegularClassSymbol
    return buildSimpleAnnotation { originAnnotationSymbol }
      .apply {
        replaceArgumentMapping(
          buildAnnotationArgumentMapping {
            mapping[StandardNames.DEFAULT_VALUE_PARAMETER] =
              buildClassReference(session, originClassId)
            if (context != null) {
              mapping[Symbols.Names.context] =
                buildLiteralExpression(
                  source = null,
                  kind = ConstantValueKind.String,
                  value = context,
                  setType = true,
                )
            }
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

      /** Resolved qualifier annotation, checking binding type ref first then class declaration. */
      val qualifier: MetroFirAnnotation?

      /** Resolved map key annotation, checking binding type ref first then class declaration. */
      val mapKey: MetroFirAnnotation?
    }

    data class ContributesTo(override val origin: ClassId) : Contribution

    /**
     * Qualifier and map key annotations resolved from the binding type ref or class declaration.
     */
    data class BindingAnnotations(
      val qualifier: MetroFirAnnotation?,
      val mapKey: MetroFirAnnotation?,
    )

    class ContributesBinding(
      override val annotatedType: FirClassSymbol<*>,
      override val annotation: FirAnnotation,
      resolveBindingAnnotations: () -> BindingAnnotations,
      override val buildAnnotations: () -> List<FirAnnotation>,
    ) : Contribution, BindingContribution {
      override val origin: ClassId = annotatedType.classId
      override val callableName: String = "binds"
      private val metaAnnotations by lazy(resolveBindingAnnotations)
      override val qualifier: MetroFirAnnotation?
        get() = metaAnnotations.qualifier

      override val mapKey: MetroFirAnnotation?
        get() = metaAnnotations.mapKey
    }

    class ContributesIntoSetBinding(
      override val annotatedType: FirClassSymbol<*>,
      override val annotation: FirAnnotation,
      resolveBindingAnnotations: () -> BindingAnnotations,
      override val buildAnnotations: () -> List<FirAnnotation>,
    ) : Contribution, BindingContribution {
      override val origin: ClassId = annotatedType.classId
      override val callableName: String = "bindIntoSet"
      private val metaAnnotations by lazy(resolveBindingAnnotations)
      override val qualifier: MetroFirAnnotation?
        get() = metaAnnotations.qualifier

      override val mapKey: MetroFirAnnotation?
        get() = metaAnnotations.mapKey
    }

    class ContributesIntoMapBinding(
      override val annotatedType: FirClassSymbol<*>,
      override val annotation: FirAnnotation,
      resolveBindingAnnotations: () -> BindingAnnotations,
      override val buildAnnotations: () -> List<FirAnnotation>,
    ) : Contribution, BindingContribution {
      override val origin: ClassId = annotatedType.classId
      override val callableName: String = "bindIntoMap"
      private val metaAnnotations by lazy(resolveBindingAnnotations)
      override val qualifier: MetroFirAnnotation?
        get() = metaAnnotations.qualifier

      override val mapKey: MetroFirAnnotation?
        get() = metaAnnotations.mapKey
    }
  }
}
