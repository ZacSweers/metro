// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.GeneratedInjectClassData
import dev.zacsweers.metro.compiler.api.fir.MetroContributionHintExtension
import dev.zacsweers.metro.compiler.api.fir.MetroContributionHintExtension.ContributionHint
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.api.fir.MetroOriginData
import dev.zacsweers.metro.compiler.api.fir.metroGeneratedInjectClassData
import dev.zacsweers.metro.compiler.api.fir.metroOriginData
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.caching
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.fir.resolveClassId
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/** Whether Circuit serializer registration declarations must be visible in FIR. */
internal val MetroOptions.generateCircuitSerializerRegistrationsInFir: Boolean
  get() = !generateClassesInIr || (generateContributionHints && generateContributionHintsInFir)

/** Generates Circuit serializer registrations in FIR. */
@OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class)
public class CircuitSerializableFirExtension(
  session: FirSession,
  compatContext: CompatContext,
  private val expectDeclarationsOnly: Boolean = false,
) :
  MetroFirDeclarationGenerationExtension(session),
  MetroContributionHintExtension,
  CompatContext by compatContext {

  private val annotatedSerializableClasses: List<FirRegularClassSymbol> by lazy {
    session.findCircuitSerializableClasses().filter {
      !expectDeclarationsOnly || it.rawStatus.isExpect
    }
  }

  private val serializerRegistrationClassIds = mutableMapOf<ClassId, FirRegularClassSymbol>()
  private val generatedClassIds = mutableSetOf<ClassId>()
  private val computedTargets = mutableMapOf<ClassId, CircuitSerializerRegistrationTarget?>()
  private val typeResolverFactory by lazy { MetroFirTypeResolver.Factory(session).caching() }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(CircuitSymbols.circuitSerializablePredicate)
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    return registerSerializerRegistrationClassIds()
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    val target = getOrComputeTarget(classId) ?: return null
    return generateSerializerRegistrationClass(target)
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    if (classSymbol.classId !in generatedClassIds) return emptySet()
    return setOf(SpecialNames.INIT)
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    if (computedTargets[context.owner.classId] == null) return emptyList()
    return listOf(
      createConstructor(
          context.owner,
          CircuitOrigins.SerializerRegistrationConstructor,
          isPrimary = true,
          generateDelegatedNoArgConstructorCall = true,
        ) {
          visibility = Visibilities.Public
        }
        .symbol
    )
  }

  override fun getContributionHints(): List<ContributionHint> {
    return registerSerializerRegistrationClassIds().mapNotNull { registrationClassId ->
      val target = getOrComputeTarget(registrationClassId) ?: return@mapNotNull null
      ContributionHint(
        contributingClassId = target.registrationClassId,
        scope = target.scopeClassId,
      )
    }
  }

  private fun generateSerializerRegistrationClass(
    target: CircuitSerializerRegistrationTarget
  ): FirClassLikeSymbol<*> {
    val registrationClass =
      createTopLevelClass(
        target.registrationClassId,
        CircuitOrigins.SerializerRegistrationClass(target.serializedType.classId),
        classKind = ClassKind.CLASS,
      ) {
        visibility = Visibilities.Public
        superType(CircuitClassIds.CircuitSerializerRegistration.constructClassLikeType())
      }

    registrationClass.metroGeneratedInjectClassData =
      GeneratedInjectClassData(hasConstructorParams = false)
    registrationClass.metroOriginData = MetroOriginData(target.serializedType.classId)

    context(session.compatContext) {
      registrationClass.replaceAnnotationsSafe(
        listOf(
          session.buildCircuitInjectAnnotation(),
          session.buildCircuitContributesIntoSetAnnotation(target.scopeClassId),
        )
      )
    }
    registrationClass.markAsDeprecatedHidden(session)
    generatedClassIds.add(registrationClass.symbol.classId)

    return registrationClass.symbol
  }

  private fun registerSerializerRegistrationClassIds(): Set<ClassId> {
    for (serializedType in annotatedSerializableClasses) {
      val registrationClassId = serializedType.classId.circuitSerializerRegistrationClassId()
      // Collisions are reported by CircuitSerializableClassChecker. Keep generation deterministic
      // so an invalid source program does not fail inside declaration generation first.
      serializerRegistrationClassIds.putIfAbsent(registrationClassId, serializedType)
    }
    return serializerRegistrationClassIds.keys
  }

  private fun getOrComputeTarget(
    registrationClassId: ClassId
  ): CircuitSerializerRegistrationTarget? {
    return computedTargets.getOrPut(registrationClassId) {
      val serializedType =
        serializerRegistrationClassIds[registrationClassId] ?: return@getOrPut null
      val typeResolver = typeResolverFactory.create(serializedType) ?: return@getOrPut null
      val annotation =
        serializedType
          .annotationsIn(session, setOf(CircuitClassIds.CircuitSerializable))
          .firstOrNull() ?: return@getOrPut null
      if (annotation !is FirAnnotationCall) return@getOrPut null
      val scopeClassId =
        annotation
          .argumentAsOrNull<FirGetClassCall>(session, CircuitNames.scope, 0)
          ?.resolveClassId(typeResolver) ?: return@getOrPut null
      CircuitSerializerRegistrationTarget(
        serializedType = serializedType,
        registrationClassId = registrationClassId,
        scopeClassId = scopeClassId,
      )
    }
  }

  public class Factory : MetroFirDeclarationGenerationExtension.Factory {
    override fun create(
      session: FirSession,
      options: MetroOptions,
      compatContext: CompatContext,
    ): MetroFirDeclarationGenerationExtension? {
      if (!options.enableCircuitCodegen) return null
      if (!options.generateCircuitSerializerRegistrationsInFir) return null
      return CircuitSerializableFirExtension(session, compatContext)
    }
  }
}

private data class CircuitSerializerRegistrationTarget(
  val serializedType: FirRegularClassSymbol,
  val registrationClassId: ClassId,
  val scopeClassId: ClassId,
)
