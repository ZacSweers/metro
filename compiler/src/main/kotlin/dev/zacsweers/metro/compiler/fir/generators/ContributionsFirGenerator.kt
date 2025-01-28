/*
 * Copyright (C) 2025 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.buildSimpleAnnotation
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.hintClassId
import dev.zacsweers.metro.compiler.fir.mapKeyAnnotation
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.joinSimpleNames
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.buildUnaryArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildClassReferenceExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildGetClassCall
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

internal class ContributionsFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  private val contributesAnnotationPredicate by unsafeLazy {
    annotated(session.classIds.allContributesAnnotations.map { it.asSingleFqName() })
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(contributesAnnotationPredicate)
  }

  private val classIdsToContributions = mutableMapOf<ClassId, MutableSet<Contribution>>()

  /**
   * [getTopLevelClassIds] will be called multiple times if we resolve any annotation's class ID,
   * which is annoying. To avoid infinite looping we track this reentrant behavior with this
   * boolean.
   */
  private var reentrant = false

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

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    if (reentrant) return emptySet()
    reentrant = true
    // TODO can we do this without the cache? This gets recalled many times
    classIdsToContributions.clear()

    val ids = mutableSetOf<ClassId>()

    for (contributingSymbol in
      session.predicateBasedProvider
        .getSymbolsByPredicate(contributesAnnotationPredicate)
        .toSet()) {
      when (contributingSymbol) {
        is FirRegularClassSymbol -> {
          classIdsToContributions.putAll(findContributions(contributingSymbol, ids))
        }
        else -> {
          error("Unsupported contributing symbol type: ${contributingSymbol.javaClass}")
        }
      }
    }

    reentrant = false
    return ids
  }

  private fun findContributions(
    contributingSymbol: FirRegularClassSymbol,
    ids: MutableSet<ClassId>,
  ): Map<ClassId, MutableSet<Contribution>> {
    val contributesToAnnotations = session.classIds.contributesToAnnotations
    val contributesBindingAnnotations = session.classIds.contributesBindingAnnotations
    val contributesIntoSetAnnotations = session.classIds.contributesIntoSetAnnotations
    val contributesIntoMapAnnotations = session.classIds.contributesIntoMapAnnotations
    val classIdsToContributions = mutableMapOf<ClassId, MutableSet<Contribution>>()
    for (annotation in contributingSymbol.annotations.filter { it.isResolved }) {
      val annotationClassId = annotation.toAnnotationClassIdSafe(session) ?: continue
      when (annotationClassId) {
        in contributesToAnnotations -> {
          val newId = contributingSymbol.classId.hintClassId
          classIdsToContributions.getOrPut(newId, ::mutableSetOf) +=
            Contribution.ContributesTo(contributingSymbol.classId)
          ids += newId
        }
        in contributesBindingAnnotations -> {
          val newId = contributingSymbol.classId.hintClassId
          classIdsToContributions.getOrPut(newId, ::mutableSetOf) +=
            Contribution.ContributesBinding(
              contributingSymbol,
              annotation,
              { listOf(buildBindsAnnotation()) },
            )
          ids += newId
        }
        in contributesIntoSetAnnotations -> {
          val newId = contributingSymbol.classId.hintClassId
          classIdsToContributions.getOrPut(newId, ::mutableSetOf) +=
            Contribution.ContributesIntoSetBinding(
              contributingSymbol,
              annotation,
              { listOf(buildIntoSetAnnotation(), buildBindsAnnotation()) },
            )
          ids += newId
        }
        in contributesIntoMapAnnotations -> {
          val newId = contributingSymbol.classId.hintClassId
          classIdsToContributions.getOrPut(newId, ::mutableSetOf) +=
            Contribution.ContributesIntoMapBinding(
              contributingSymbol,
              annotation,
              { listOf(buildIntoMapAnnotation(), buildBindsAnnotation()) },
            )
          ids += newId
        }
      }
    }
    return classIdsToContributions
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    val contributions = classIdsToContributions[classId] ?: return null
    return createTopLevelClass(classId, key = Keys.Default, classKind = ClassKind.INTERFACE) {
        // annoyingly not implicit from the class kind
        modality = Modality.ABSTRACT
        for (contribution in contributions) {
          if (contribution is Contribution.ContributesTo) {
            superType(contribution.origin.defaultType(emptyList()))
          }
        }
      }
      .apply { replaceAnnotationsSafe(listOf(buildOriginAnnotation(contributions.first().origin))) }
      .symbol
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext
  ): Set<Name> {
    return super.getNestedClassifiersNames(classSymbol, context)
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext
  ): FirClassLikeSymbol<*>? {
    return super.generateNestedClassLikeDeclaration(owner, name, context)
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    val classId = classSymbol.classId
    val contributions = classIdsToContributions[classId] ?: return emptySet()
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
    val contributions = classIdsToContributions[callableId.classId] ?: return emptyList()
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

  private fun buildOriginAnnotation(origin: ClassId): FirAnnotation {
    return buildAnnotation {
      val originAnno = session.metroFirBuiltIns.originClassSymbol

      annotationTypeRef = originAnno.defaultType().toFirResolvedTypeRef()

      argumentMapping = buildAnnotationArgumentMapping {
        mapping[Name.identifier("value")] = buildGetClassCall {
          val lookupTag = origin.toLookupTag()
          val referencedType = lookupTag.constructType()
          val resolvedType =
            StandardClassIds.KClass.constructClassLikeType(arrayOf(referencedType), false)
          argumentList =
            buildUnaryArgumentList(
              buildClassReferenceExpression {
                classTypeRef = buildResolvedTypeRef { coneType = referencedType }
                coneTypeOrNull = resolvedType
              }
            )
          coneTypeOrNull = resolvedType
        }
      }
    }
  }
}
