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
import dev.zacsweers.lattice.compiler.capitalizeUS
import dev.zacsweers.lattice.compiler.decapitalizeUS
import dev.zacsweers.lattice.compiler.fir.LatticeFirValueParameter
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.hasOrigin
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.lattice.compiler.isWordPrefixRegex
import dev.zacsweers.lattice.compiler.mapNotNullToSet
import dev.zacsweers.lattice.compiler.unsafeLazy
import kotlin.collections.set
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.ExperimentalSupertypesGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.typeResolver
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirErrorTypeRef
import org.jetbrains.kotlin.fir.types.FirImplicitTypeRef
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/** Generates factory declarations for `@Provides`-annotated members. */
internal class ProvidesFactoryFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  private val providesAnnotationPredicate by unsafeLazy {
    annotated(session.latticeClassIds.providesAnnotations.map { it.asSingleFqName() })
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(providesAnnotationPredicate)
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
    val callable =
      if (classSymbol.hasOrigin(LatticeKeys.ProviderFactoryCompanionDeclaration)) {
        val owner = classSymbol.getContainingClassSymbol() ?: return emptySet()
        providerFactoryClassIdsToCallables[owner.classId]
      } else {
        providerFactoryClassIdsToCallables[classSymbol.classId]
      } ?: return emptySet()

    return buildSet {
      add(SpecialNames.INIT)
      if (classSymbol.classKind == ClassKind.OBJECT) {
        // Generate create() and newInstance headers
        add(LatticeSymbols.Names.create)
        add(callable.bytecodeName)
      }
    }
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val constructor =
      if (context.owner.classKind == ClassKind.OBJECT) {
        createDefaultPrivateConstructor(context.owner, LatticeKeys.Default)
      } else {
        val callable =
          providerFactoryClassIdsToCallables[context.owner.classId] ?: return emptyList()
        buildFactoryConstructor(context, callable.instanceReceiver, null, callable.valueParameters)
      }
    return listOf(constructor.symbol)
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val nonNullContext = context ?: return emptyList()
    val factoryClassId =
      if (nonNullContext.owner.isCompanion) {
        nonNullContext.owner.getContainingClassSymbol()?.classId ?: return emptyList()
      } else {
        nonNullContext.owner.classId
      }
    val callable = providerFactoryClassIdsToCallables[factoryClassId] ?: return emptyList()
    val function =
      when (callableId.callableName) {
        LatticeSymbols.Names.create -> {
          buildFactoryCreateFunction(
            nonNullContext,
            LatticeSymbols.ClassIds.latticeFactory.constructClassLikeType(
              arrayOf(callable.returnType)
            ),
            callable.instanceReceiver,
            null,
            callable.valueParameters,
          )
        }
        callable.bytecodeName -> {
          buildNewInstanceFunction(
            nonNullContext,
            callable.bytecodeName,
            callable.returnType,
            callable.instanceReceiver,
            null,
            callable.valueParameters,
          )
        }
        else -> {
          println("Unrecognized function $callableId")
          return emptyList()
        }
      }
    return listOf(function)
  }

  // TODO can we get a finer-grained callback other than just per-class?
  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    return if (classSymbol.hasOrigin(LatticeKeys.ProviderFactoryCompanionDeclaration)) {
      // It's a factory's companion object
      emptySet()
    } else if (classSymbol.classId in providerFactoryClassIdsToCallables) {
      // It's a generated factory, give it a companion object if it isn't going to be an object
      if (classSymbol.classKind == ClassKind.OBJECT) {
        emptySet()
      } else {
        setOf(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
      }
    } else {
      // It's a provider-containing class, generated factory class names and store callable info
      classSymbol.declarationSymbols
        .filterIsInstance<FirCallableSymbol<*>>()
        .filter {
          it.isAnnotatedWithAny(session, session.latticeClassIds.providesAnnotations) ||
            (it as? FirPropertySymbol)
              ?.getterSymbol
              ?.isAnnotatedWithAny(session, session.latticeClassIds.providesAnnotations) == true
        }
        .mapNotNullToSet { providesCallable ->
          val providerCallable =
            providesCallable.asProviderCallable(classSymbol) ?: return@mapNotNullToSet null
          val simpleName =
            buildString {
                if (providerCallable.useGetPrefix) {
                  append("Get")
                }
                append(providerCallable.name.capitalizeUS())
                append(LatticeSymbols.Names.latticeFactory.asString())
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
      createCompanionObject(owner, LatticeKeys.ProviderFactoryCompanionDeclaration).symbol
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
          LatticeKeys.ProviderFactoryClassDeclaration,
          classKind = classKind,
        )
        .apply { markAsDeprecatedHidden(session) }
        .symbol
        .also { providerFactoryClassIdsToSymbols[it.classId] = it }
    } else {
      null
    }
  }

  private fun FirCallableSymbol<*>.asProviderCallable(owner: FirClassSymbol<*>): ProviderCallable? {
    val instanceReceiver = if (owner.isCompanion) null else owner.defaultType()
    val params =
      when (this) {
        is FirPropertySymbol -> emptyList()
        is FirNamedFunctionSymbol ->
          this.valueParameterSymbols.map { LatticeFirValueParameter(session, it) }
        else -> return null
      }
    return ProviderCallable(owner, this, instanceReceiver, params)
  }

  class ProviderCallable(
    val owner: FirClassSymbol<*>,
    val symbol: FirCallableSymbol<*>,
    val instanceReceiver: ConeClassLikeType?,
    val valueParameters: List<LatticeFirValueParameter>,
  ) {
    val callableId = CallableId(owner.classId, symbol.name)
    val name = symbol.name
    val shouldGenerateObject by unsafeLazy {
      instanceReceiver == null && (isProperty || valueParameters.isEmpty())
    }
    private val isProperty
      get() = symbol is FirPropertySymbol

    val returnType
      get() = symbol.resolvedReturnType

    val useGetPrefix by unsafeLazy { isProperty && !isWordPrefixRegex.matches(name.asString()) }

    val bytecodeName: Name by unsafeLazy {
      buildString {
          when {
            useGetPrefix -> {
              append("get")
              append(name.asString().capitalizeUS())
            }
            else -> append(name.asString())
          }
        }
        .asName()
    }
  }
}

internal class ProvidesFactorySupertypeGenerator(session: FirSession) :
  FirSupertypeGenerationExtension(session) {

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    return declaration.symbol.hasOrigin(LatticeKeys.ProviderFactoryClassDeclaration)
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> = emptyList()

  @OptIn(SymbolInternals::class)
  @ExperimentalSupertypesGenerationApi
  override fun computeAdditionalSupertypesForGeneratedNestedClass(
    klass: FirRegularClass,
    typeResolver: TypeResolveService,
  ): List<FirResolvedTypeRef> {
    val originClassSymbol =
      klass.getContainingClassSymbol() as? FirClassSymbol<*> ?: return emptyList()
    val callableName =
      klass.name
        .asString()
        .removeSuffix(LatticeSymbols.Names.latticeFactory.asString())
        .decapitalizeUS()
    val callable =
      originClassSymbol.declarationSymbols.filterIsInstance<FirCallableSymbol<*>>().firstOrNull {
        it.name.asString() == callableName ||
          (it is FirPropertySymbol &&
            it.name.asString() == callableName.removePrefix("get").decapitalizeUS())
      } ?: return emptyList()

    val returnType =
      when (val type = callable.fir.returnTypeRef) {
        is FirUserTypeRef -> {
          typeResolver.resolveUserType(type).also {
            if (it is FirErrorTypeRef) {
              val message = buildString {
                appendLine(
                  "Could not resolve provider return type for provider: ${callable.callableId}"
                )
                appendLine(
                  "This can happen if the provider references a class that is nested within the same parent class and has cyclical references to other classes."
                )
                appendLine(callable.fir.render())
              }
              error(message)
            }
          }
        }
        is FirResolvedTypeRef -> type
        is FirImplicitTypeRef -> {
          // Ignore, will report in FIR checker
          return emptyList()
        }
        else -> return emptyList()
      }

    val factoryType =
      session.symbolProvider
        .getClassLikeSymbolByClassId(LatticeSymbols.ClassIds.latticeFactory)!!
        .constructType(arrayOf(returnType.coneType))
    return listOf(factoryType.toFirResolvedTypeRef())
  }
}
