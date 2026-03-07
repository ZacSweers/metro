// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.MetroFirValueParameter
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.copyTypeParametersFrom
import dev.zacsweers.metro.compiler.fir.hasOrigin
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.impl.FirBackingFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertyAccessorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.ConstantValueKind

/** Generates factory declarations for `@Provides`-annotated members. */
internal class ProvidesFactoryFirGenerator(session: FirSession, compatContext: CompatContext) :
  FirDeclarationGenerationExtension(session), CompatContext by compatContext {

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(session.predicates.providesAnnotationPredicate)
  }

  // TODO apparently writing these types of caches is bad and
  //  generate* functions should be side-effect-free, but honestly
  //  how is this practical without this? Or is it ok if it's just an
  //  internal cache? Unclear what "should not leak" means.
  private val providerFactoryClassIdsToCallables = mutableMapOf<ClassId, ProviderCallable>()
  private val providerFactoryClassIdsToSymbols = mutableMapOf<ClassId, FirClassLikeSymbol<*>>()

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    val shouldHandle =
      // Is it one of our factories or their companion objects?
      (classSymbol.hasOrigin(Keys.ProviderFactoryCompanionDeclaration) ||
        classSymbol.hasOrigin(Keys.ProviderFactoryClassDeclaration)) &&
        // Only if it's an object class type. Regular constructors will be generated in IR
        classSymbol.classKind == ClassKind.OBJECT
    return if (shouldHandle) {
      setOf(SpecialNames.INIT)
    } else {
      emptySet()
    }
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val constructor =
      if (context.owner.classKind == ClassKind.OBJECT) {
        createDefaultPrivateConstructor(context.owner, Keys.Default)
      } else {
        return emptyList()
      }
    return listOf(constructor.symbol)
  }

  // TODO can we get a finer-grained callback other than just per-class?
  @OptIn(DirectDeclarationsAccess::class)
  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    return if (classSymbol.hasOrigin(Keys.ProviderFactoryCompanionDeclaration)) {
      // It's a factory's companion object
      emptySet()
    } else if (classSymbol.classId in providerFactoryClassIdsToCallables) {
      // It's a generated factory, give it a companion object if it isn't going to be an object
      if (classSymbol.classKind.isObject) {
        emptySet()
      } else {
        setOf(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
      }
    } else {
      // It's a provider-containing class, generated factory class names and store callable info
      classSymbol.declarationSymbols
        .filterIsInstance<FirCallableSymbol<*>>()
        .filter {
          it.isAnnotatedWithAny(session, session.classIds.providesAnnotations) ||
            (it as? FirPropertySymbol)
              ?.getterSymbol
              ?.isAnnotatedWithAny(session, session.classIds.providesAnnotations) == true
        }
        .mapNotNullToSet { providesCallable ->
          val providerCallable =
            providesCallable.asProviderCallable(classSymbol) ?: return@mapNotNullToSet null
          val simpleName =
            buildString {
                append(providerCallable.name.capitalizeUS())
                append(Symbols.Names.MetroFactory.asString())
              }
              .asName()
          simpleName.also {
            providerFactoryClassIdsToCallables[
              classSymbol.classId.createNestedClassId(simpleName)] = providerCallable
          }
        }
    }
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    return if (name == SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) {
      // It's a factory's companion object, just generate the declaration
      createCompanionObject(owner, Keys.ProviderFactoryCompanionDeclaration).symbol
    } else if (owner.classId.createNestedClassId(name) in providerFactoryClassIdsToCallables) {
      // It's a factory class itself
      val classId = owner.classId.createNestedClassId(name)
      val sourceCallable = providerFactoryClassIdsToCallables[classId] ?: return null

      val classKind =
        if (sourceCallable.shouldGenerateObject) {
          ClassKind.OBJECT
        } else {
          ClassKind.CLASS
        }

      createNestedClass(
          owner,
          name.capitalizeUS(),
          Keys.ProviderFactoryClassDeclaration,
          classKind = classKind,
        ) {
          copyTypeParametersFrom(owner, session)
        }
        .apply {
          markAsDeprecatedHidden(session)
          // Add the source callable info
          replaceAnnotationsSafe(
            annotations + listOf(buildCallableMetadataAnnotation(sourceCallable))
          )
        }
        .symbol
        .also { providerFactoryClassIdsToSymbols[it.classId] = it }
    } else {
      null
    }
  }

  private fun FirCallableSymbol<*>.asProviderCallable(owner: FirClassSymbol<*>): ProviderCallable? {
    val instanceReceiver = if (owner.classKind.isObject) null else owner.defaultType()
    val params =
      when (this) {
        is FirPropertySymbol -> emptyList()
        is FirNamedFunctionSymbol ->
          this.valueParameterSymbols.map { MetroFirValueParameter(session, it) }
        else -> return null
      }
    return ProviderCallable(owner, this, instanceReceiver, params)
  }

  private fun buildCallableMetadataAnnotation(sourceCallable: ProviderCallable): FirAnnotation {
    return buildAnnotation {
      val anno = session.metroFirBuiltIns.callableMetadataClassSymbol

      annotationTypeRef = anno.defaultType().toFirResolvedTypeRef()

      argumentMapping = buildAnnotationArgumentMapping {
        mapping[Name.identifier("callableName")] =
          buildLiteralExpression(
            source = null,
            kind = ConstantValueKind.String,
            value = sourceCallable.callableId.callableName.asString(),
            annotations = null,
            setType = true,
            prefix = null,
          )

        val symbolToMap =
          when (val symbol = sourceCallable.symbol) {
            is FirPropertyAccessorSymbol -> symbol.propertySymbol
            is FirPropertySymbol -> symbol
            is FirNamedFunctionSymbol -> symbol
            is FirBackingFieldSymbol -> symbol.propertySymbol
            is FirFieldSymbol -> symbol
            else -> reportCompilerBug("Unexpected callable symbol type: $symbol")
          }

        // Only set propertyName if it's a property
        val propertyName =
          if (symbolToMap !is FirNamedFunctionSymbol) {
            symbolToMap.name.asString()
          } else {
            ""
          }
        mapping[Name.identifier("propertyName")] =
          buildLiteralExpression(
            source = null,
            kind = ConstantValueKind.String,
            value = propertyName,
            annotations = null,
            setType = true,
            prefix = null,
          )

        mapping[Name.identifier("startOffset")] =
          buildLiteralExpression(
            source = null,
            kind = ConstantValueKind.Int,
            value = symbolToMap.source?.startOffset ?: UNDEFINED_OFFSET,
            annotations = null,
            setType = true,
            prefix = null,
          )

        mapping[Name.identifier("endOffset")] =
          buildLiteralExpression(
            source = null,
            kind = ConstantValueKind.Int,
            value = symbolToMap.source?.endOffset ?: UNDEFINED_OFFSET,
            annotations = null,
            setType = true,
            prefix = null,
          )

        mapping[Name.identifier("newInstanceName")] =
          buildLiteralExpression(
            source = null,
            kind = ConstantValueKind.String,
            value = sourceCallable.newInstanceName.asString(),
            annotations = null,
            setType = true,
            prefix = null,
          )
      }
    }
  }

  class ProviderCallable(
    val owner: FirClassSymbol<*>,
    val symbol: FirCallableSymbol<*>,
    val instanceReceiver: ConeClassLikeType?,
    val valueParameters: List<MetroFirValueParameter>,
  ) {
    val callableId = CallableId(owner.classId, symbol.name)
    val name = symbol.name
    val shouldGenerateObject by memoize {
      instanceReceiver == null && (isProperty || valueParameters.isEmpty())
    }
    private val isProperty
      get() = symbol is FirPropertySymbol

    val returnType
      get() = symbol.resolvedReturnType

    val newInstanceName: Name
      get() = name
  }
}
