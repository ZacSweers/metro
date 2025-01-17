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
package dev.zacsweers.lattice.compiler.fir.generators

import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.asName
import dev.zacsweers.lattice.compiler.expectAsOrNull
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.argumentAsOrNull
import dev.zacsweers.lattice.compiler.fir.hintClassId
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.latticeFirBuiltIns
import dev.zacsweers.lattice.compiler.fir.qualifierAnnotation
import dev.zacsweers.lattice.compiler.unsafeLazy
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
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

internal class ContributionsFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  // Symbols for classes which have contributing annotations.
  private val contributingSymbols by lazy {
    session.predicateBasedProvider.getSymbolsByPredicate(contributesAnnotationPredicate).toSet()
  }

  private val contributesAnnotationPredicate by unsafeLazy {
    annotated(session.latticeClassIds.allContributesAnnotations.map { it.asSingleFqName() })
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(contributesAnnotationPredicate)
  }

  private val classIdsToContributions = mutableMapOf<ClassId, MutableSet<Contribution>>()

  sealed interface Contribution {
    val origin: ClassId

    data class ContributesTo(override val origin: ClassId) : Contribution

    data class ContributesBinding(
      val annotatedType: FirClassSymbol<*>,
      val annotation: FirAnnotation,
    ) : Contribution {
      override val origin: ClassId = annotatedType.classId
    }
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    val contributesToAnnotations = session.latticeClassIds.contributesToAnnotations
    val contributesBindingAnnotations = session.latticeClassIds.contributesBindingAnnotations

    // TODO the others!
    val contributesIntoSetAnnotations = session.latticeClassIds.contributesIntoSetAnnotations
    val contributesIntoMapAnnotations = session.latticeClassIds.contributesIntoMapAnnotations

    val ids = mutableSetOf<ClassId>()


    for (contributingSymbol in contributingSymbols) {
      when (contributingSymbol) {
        is FirRegularClassSymbol -> {
          for (annotation in contributingSymbol.resolvedAnnotationsWithClassIds) {
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
                  Contribution.ContributesBinding(contributingSymbol, annotation)
                ids += newId
              }
            }
          }
        }
        else -> {
          error("Unsupported contributing symbol type: ${contributingSymbol.javaClass}")
        }
      }
    }

    return ids
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    val contributions = classIdsToContributions[classId] ?: return null
    return createTopLevelClass(
        classId,
        key = LatticeKeys.Default,
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
      .apply { replaceAnnotations(listOf(buildOriginAnnotation(contributions.first().origin))) }
      .symbol
  }

  private fun buildBindsAnnotation(): FirAnnotation {
    return buildAnnotation {
      annotationTypeRef =
        session.latticeFirBuiltIns.bindsClassSymbol.defaultType().toFirResolvedTypeRef()

      argumentMapping = buildAnnotationArgumentMapping()
    }
  }

  private fun buildOriginAnnotation(origin: ClassId): FirAnnotation {
    return buildAnnotation {
      val originAnno = session.latticeFirBuiltIns.originClassSymbol

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

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    val classId = classSymbol.classId
    val contributions = classIdsToContributions[classId] ?: return emptySet()
    val names = mutableSetOf<Name>()
    for (contribution in contributions) {
      if (contribution is Contribution.ContributesBinding) {
        if (contribution.annotatedType is FirClassSymbol) {
          names += "bind".asName()
        }
      }
    }
    return names
  }

  private fun FirAnnotation.boundTypeOrNull(): FirTypeRef? {
    return argumentAsOrNull<FirFunctionCall>("boundType".asName(), 1)
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
    if (callableId.callableName != "bind".asName()) return emptyList()
    val contributions = classIdsToContributions[callableId.classId] ?: return emptyList()
    val properties = mutableListOf<FirPropertySymbol>()
    for (contribution in contributions) {
      if (contribution is Contribution.ContributesBinding) {
        val boundTypeRef = contribution.annotation.boundTypeOrNull()
        // Standard annotation on the class itself, look for a single bound type
        val boundType =
          (boundTypeRef ?: contribution.annotatedType.resolvedSuperTypeRefs.single()).coneType

        val classQualifierAnnotation = if (boundTypeRef == null) {
          contribution.annotatedType
            .qualifierAnnotation(session)
            ?.fir
        } else {
          null
        }

        properties +=
          createMemberProperty(
              owner,
              LatticeKeys.Default,
              "bind".asName(),
              returnType = boundType,
              hasBackingField = false,
            ) {
              modality = Modality.ABSTRACT
              extensionReceiverType(contribution.origin.defaultType(emptyList()))
            }
            .apply {
              replaceAnnotations(
                buildList {
                  add(buildBindsAnnotation())
                  // If we came from a BoundType value, copy over its annotations
                  // TODO check in FIR that these are only qualifiers
                  boundTypeRef?.annotations?.let(::addAll)
                  classQualifierAnnotation?.let(::add)
                }
              )
            }
            .symbol
      }
    }
    return properties
  }

  override fun hasPackage(packageFqName: FqName): Boolean {
    return if (packageFqName == LatticeSymbols.FqNames.latticeHintsPackage) {
      true
    } else {
      super.hasPackage(packageFqName)
    }
  }
}
