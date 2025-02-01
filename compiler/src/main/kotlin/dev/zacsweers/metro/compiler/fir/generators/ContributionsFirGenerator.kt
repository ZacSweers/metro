// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.buildSimpleAnnotation
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.hasOrigin
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.mapKeyAnnotation
import dev.zacsweers.metro.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.joinSimpleNames
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class ContributionsFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  private val contributesAnnotationPredicate by unsafeLazy {
    annotated(session.classIds.allContributesAnnotations.map { it.asSingleFqName() })
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(contributesAnnotationPredicate)
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
      override val callableName: String = "bind"
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
    for (annotation in contributingSymbol.annotations.filter { it.isResolved }) {
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
      }
    }

    return if (contributions.isEmpty()) {
      null
    } else {
      contributions
    }
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    return if (
      classSymbol.isAnnotatedWithAny(session, session.classIds.allContributesAnnotations)
    ) {
      setOf(Symbols.Names.metroContribution)
    } else {
      emptySet()
    }
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (name != Symbols.Names.metroContribution) return null
    val contributions = findContributions(owner) ?: return null
    return createNestedClass(
        owner,
        name = name,
        key = Keys.MetroContributionDeclaration,
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
      .apply { markAsDeprecatedHidden(session) }
      .symbol
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    if (!classSymbol.hasOrigin(Keys.MetroContributionDeclaration)) return emptySet()
    val origin = classSymbol.getContainingClassSymbol() as? FirClassSymbol<*> ?: return emptySet()
    val contributions = findContributions(origin) ?: return emptySet()
    // Note the names we supply here are not final, we just need to know if we're going to generate
    // _any_ names for this type. We will return n >= 1 properties in generateProperties later.
    return contributions
      .filterIsInstance<Contribution.BindingContribution>()
      .groupBy { it.callableName.asName() }
      .keys
  }

  private fun FirAnnotation.boundTypeOrNull(): FirTypeRef? {
    return argumentAsOrNull<FirFunctionCall>("boundType".asName(), 2)
      ?.typeArguments
      ?.getOrNull(0)
      ?.expectAsOrNull<FirTypeProjectionWithVariance>()
      ?.typeRef
      ?.takeUnless { it == session.builtinTypes.nothingType }
  }

  override fun generateProperties(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirPropertySymbol> {
    val owner = context?.owner ?: return emptyList()
    if (!owner.hasOrigin(Keys.MetroContributionDeclaration)) return emptyList()
    val origin = owner.getContainingClassSymbol() as? FirClassSymbol<*> ?: return emptyList()
    val contributions = findContributions(origin) ?: return emptyList()
    val properties =
      contributions.mapNotNull { contribution ->
        when (contribution) {
          is Contribution.ContributesBinding,
          is Contribution.ContributesIntoSetBinding,
          is Contribution.ContributesIntoMapBinding -> {
            buildBindingProperty(owner, contribution)
          }
          is Contribution.ContributesTo -> null
        }
      }
    return properties
  }

  private fun buildBindingProperty(
    owner: FirClassSymbol<*>,
    contribution: Contribution.BindingContribution,
  ): FirPropertySymbol {
    val boundTypeRef = contribution.annotation.boundTypeOrNull()
    // Standard annotation on the class itself, look for a single bound type
    val boundType =
      (boundTypeRef ?: contribution.annotatedType.resolvedSuperTypeRefs.single()).coneType

    val qualifier =
      if (boundTypeRef == null) {
        contribution.annotatedType.qualifierAnnotation(session)
      } else {
        boundTypeRef.annotations.qualifierAnnotation(session)
      }

    val mapKey =
      if (boundTypeRef == null) {
        contribution.annotatedType.mapKeyAnnotation(session)
      } else {
        boundTypeRef.annotations.mapKeyAnnotation(session)
      }

    val suffix = buildString {
      qualifier?.hashString()?.let(::append)

      boundType.classId
        ?.joinSimpleNames("", camelCase = true)
        ?.shortClassName
        ?.capitalizeUS()
        ?.let(::append)
    }

    return createMemberProperty(
        owner,
        Keys.Default,
        (contribution.callableName + "As" + suffix).asName(),
        returnType = boundType,
        hasBackingField = false,
      ) {
        modality = Modality.ABSTRACT
        extensionReceiverType(contribution.origin.defaultType(emptyList()))
      }
      .also { property ->
        val newAnnotations = mutableListOf<FirAnnotation>()
        newAnnotations.addAll(contribution.buildAnnotations())
        qualifier?.fir?.let(newAnnotations::add)
        mapKey?.fir?.let(newAnnotations::add)
        property.replaceAnnotationsSafe(newAnnotations)
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
}
