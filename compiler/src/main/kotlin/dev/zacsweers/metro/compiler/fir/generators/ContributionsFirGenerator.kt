// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.buildSimpleAnnotation
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.hasOrigin
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isBindingContainer
import dev.zacsweers.metro.compiler.fir.mapKeyAnnotation
import dev.zacsweers.metro.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.resolvedClassId
import dev.zacsweers.metro.compiler.fir.scopeArgument
import dev.zacsweers.metro.compiler.joinSimpleNamesAndTruncate
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.native.interop.parentsWithSelf
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.toReference
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.references.impl.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

// TODO a bunch of this could probably be cleaned up now that the functions are generated in IR
/**
 * Generates `@MetroContribution`-annotated nested contribution classes for
 * `@Contributes*`-annotated classes.
 */
internal class ContributionsFirGenerator(session: FirSession, compatContext: CompatContext) :
  FirDeclarationGenerationExtension(session), CompatContext by compatContext {

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
          .mapNotNull { it.scopeArgument() }
          .distinctBy { it.scopeName(session) }
          .forEach { scopeArgument ->
            val suffix =
              scopeArgument.resolvedClassId()?.let { scopeClass ->
                scopeClass
                  .joinSimpleNamesAndTruncate(separator = "", camelCase = true)
                  .asSingleFqName()
                  .pathSegments()
                  .joinToString(separator = "") { it.identifier.decapitalizeUS() }
              }
                ?: scopeArgument.scopeName(session)
                ?: reportCompilerBug("Could not get scope name for ${scopeArgument.render()}")
            val nestedContributionName =
              (Symbols.StringNames.METRO_CONTRIBUTION_NAME_PREFIX + "To" + suffix.capitalizeUS())
                .asName()

            contributionNamesToScopeArgs[nestedContributionName] = scopeArgument
          }
      }
      contributionNamesToScopeArgs
    }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(session.predicates.contributesAnnotationPredicate)
    register(session.predicates.bindingContainerPredicate)
  }

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
            Contribution.ContributesBinding(contributingSymbol, annotation) {
              listOf(buildBindsAnnotation())
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
    return if (classSymbol.name == Symbols.Names.BindsMirrorClass) {
      setOf(SpecialNames.INIT)
    } else {
      emptySet()
    }
  }

  // TODO dedupe with BindingMirrorClassFirGenerator
  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    return if (context.owner.name == Symbols.Names.BindsMirrorClass) {
      // Private constructor to prevent instantiation
      listOf(createDefaultPrivateConstructor(context.owner, Keys.Default).symbol)
    } else {
      emptyList()
    }
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    if (context.owner.hasOrigin(Keys.MetroContributionClassDeclaration)) {
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

    // Don't generate nested classes for binding container classes
    if (classSymbol.isBindingContainer(session)) {
      return emptySet()
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

  private fun buildMetroContributionAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.metroContributionClassSymbol }
  }
}
