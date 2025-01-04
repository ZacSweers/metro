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
import dev.zacsweers.lattice.compiler.fir.FirTypeKey
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.buildSimpleValueParameter
import dev.zacsweers.lattice.compiler.fir.copyParametersWithDefaults
import dev.zacsweers.lattice.compiler.fir.generateMemberFunction
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.lattice.compiler.fir.wrapInProvider
import dev.zacsweers.lattice.compiler.isWordPrefixRegex
import dev.zacsweers.lattice.compiler.mapNotNullToSet
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.containingClassForStaticMemberAttr
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.builder.buildTypeParameterCopy
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/** Generates declarations for `@Provides`-annotated members. */
internal class ProvidesFactoryFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  private val providesAnnotationPredicate by unsafeLazy {
    annotated(session.latticeClassIds.providesAnnotations.map { it.asSingleFqName() })
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(providesAnnotationPredicate)
  }

  private val providerFactoryClassIdsToCallables = mutableMapOf<ClassId, ProviderCallable>()
  private val providerFactoryClassIdsToSymbols = mutableMapOf<ClassId, FirClassLikeSymbol<*>>()

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    val callable =
      if (classSymbol.isCompanion) {
        val owner = classSymbol.getContainingClassSymbol() ?: return emptySet()
        providerFactoryClassIdsToCallables[owner.classId]
      } else {
        providerFactoryClassIdsToCallables[classSymbol.classId]
      } ?: return emptySet()

    return buildSet {
      add(SpecialNames.INIT)
      if (!classSymbol.isCompanion) {
        add(LatticeSymbols.Names.invoke)
      }
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

  // TODO eventually extract shared logic for creating constructors, to share with Constructor
  //  factory gen
  private fun buildFactoryConstructor(
    context: MemberGenerationContext,
    instanceReceiver: ConeClassLikeType?,
    extensionReceiver: ConeClassLikeType?,
    valueParameters: List<FirValueParameterSymbol>,
  ): FirConstructor {
    val owner = context.owner
    return createConstructor(
        owner,
        LatticeKeys.Default,
        isPrimary = true,
        generateDelegatedNoArgConstructorCall = true,
      ) {
        instanceReceiver?.let {
          valueParameter(LatticeSymbols.Names.instance, it, key = LatticeKeys.InstanceParameter)
        }
        extensionReceiver?.let {
          valueParameter(
            LatticeSymbols.Names.receiver,
            it.wrapInProvider(),
            key = LatticeKeys.ReceiverParameter,
          )
        }
        for (valueParameter in valueParameters) {
          // TODO toe-hold for later factory gen
          if (
            valueParameter.isAnnotatedWithAny(session, session.latticeClassIds.assistedAnnotations)
          ) {
            continue
          }
          // TODO refactor this conversion up
          val typeKey = FirTypeKey.from(session, valueParameter)
          valueParameter(
            valueParameter.name,
            typeKey.type.coneType.wrapInProvider(),
            key = LatticeKeys.ValueParameter,
          )
        }
      }
      .also { it.containingClassForStaticMemberAttr = owner.toLookupTag() }
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val context = context ?: return emptyList()
    val factoryClassId =
      if (context.owner.isCompanion) {
        context.owner.getContainingClassSymbol()?.classId ?: return emptyList()
      } else {
        context.owner.classId
      }
    val callable = providerFactoryClassIdsToCallables[factoryClassId] ?: return emptyList()
    val function =
      when (callableId.callableName) {
        LatticeSymbols.Names.invoke -> {
          createMemberFunction(
              context.owner,
              LatticeKeys.Default,
              callableId.callableName,
              callable.returnType,
            ) {
              status { isOverride = true }
            }
            .symbol
        }
        LatticeSymbols.Names.create -> {
          buildFactoryCreateFunction(
            context,
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
            context,
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

  @OptIn(SymbolInternals::class)
  private fun buildFactoryCreateFunction(
    context: MemberGenerationContext,
    returnType: ConeKotlinType,
    instanceReceiver: ConeClassLikeType?,
    extensionReceiver: ConeClassLikeType?,
    valueParameters: List<FirValueParameterSymbol>,
  ): FirNamedFunctionSymbol {
    return generateMemberFunction(
        context.owner,
        returnType.toFirResolvedTypeRef(),
        CallableId(context.owner.classId, LatticeSymbols.Names.create),
      ) {
        val thisFunctionSymbol = symbol
        for (typeParameter in context.owner.typeParameterSymbols) {
          typeParameters += buildTypeParameterCopy(typeParameter.fir) {}
        }

        instanceReceiver?.let {
          this.valueParameters +=
            buildSimpleValueParameter(
              name = LatticeSymbols.Names.instance,
              type = it.toFirResolvedTypeRef(),
              containingFunctionSymbol = thisFunctionSymbol,
              origin = LatticeKeys.InstanceParameter.origin,
            )
        }
        extensionReceiver?.let {
          this.valueParameters +=
            buildSimpleValueParameter(
              name = LatticeSymbols.Names.receiver,
              type = it.wrapInProvider().toFirResolvedTypeRef(),
              containingFunctionSymbol = thisFunctionSymbol,
              origin = LatticeKeys.ReceiverParameter.origin,
            )
        }

        copyParametersWithDefaults(
          valueParameters.filterNot {
            it.isAnnotatedWithAny(session, session.latticeClassIds.assistedAnnotations)
          }
        ) { original ->
          val typeKey = FirTypeKey.from(session, original)
          this.returnTypeRef = typeKey.type.coneType.wrapInProvider().toFirResolvedTypeRef()
        }
      }
      .symbol
  }

  @OptIn(SymbolInternals::class)
  private fun buildNewInstanceFunction(
    context: MemberGenerationContext,
    name: Name,
    returnType: ConeKotlinType,
    instanceReceiver: ConeClassLikeType?,
    extensionReceiver: ConeClassLikeType?,
    valueParameters: List<FirValueParameterSymbol>,
  ): FirNamedFunctionSymbol {
    return generateMemberFunction(
        context.owner,
        returnType.toFirResolvedTypeRef(),
        CallableId(context.owner.classId, name),
        origin = LatticeKeys.ProviderNewInstanceFunction.origin,
      ) {
        val thisFunctionSymbol = symbol
        for (typeParameter in context.owner.typeParameterSymbols) {
          typeParameters += buildTypeParameterCopy(typeParameter.fir) {}
        }

        instanceReceiver?.let {
          this.valueParameters +=
            buildSimpleValueParameter(
              name = LatticeSymbols.Names.instance,
              type = it.toFirResolvedTypeRef(),
              containingFunctionSymbol = thisFunctionSymbol,
              origin = LatticeKeys.InstanceParameter.origin,
            )
        }
        extensionReceiver?.let {
          this.valueParameters +=
            buildSimpleValueParameter(
              name = LatticeSymbols.Names.receiver,
              type = it.toFirResolvedTypeRef(),
              containingFunctionSymbol = thisFunctionSymbol,
              origin = LatticeKeys.ReceiverParameter.origin,
            )
        }

        copyParametersWithDefaults(
          valueParameters.filterNot {
            it.isAnnotatedWithAny(session, session.latticeClassIds.assistedAnnotations)
          }
        ) { original ->
          val typeKey = FirTypeKey.from(session, original)
          this.returnTypeRef = typeKey.type.coneType.toFirResolvedTypeRef()
        }
      }
      .symbol
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    return if (classSymbol.isCompanion && classSymbol.getContainingClassSymbol()?.origin == LatticeKeys.ProviderFactoryClassDeclaration.origin) {
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
        .filter { it.isAnnotatedWithAny(session, session.latticeClassIds.providesAnnotations) }
        .mapNotNullToSet { providesCallable ->
          val providerCallable =
            ProviderCallable.create(classSymbol, providesCallable) ?: return@mapNotNullToSet null
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
      createCompanionObject(owner, LatticeKeys.Default).symbol
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
      val returnType = sourceCallable.returnType

      createNestedClass(owner, name.capitalizeUS(), LatticeKeys.ProviderFactoryClassDeclaration, classKind = classKind) {
          superType(
            LatticeSymbols.ClassIds.latticeFactory.constructClassLikeType(arrayOf(returnType))
          )
        }
        .apply { markAsDeprecatedHidden(session) }
        .symbol
        .also { providerFactoryClassIdsToSymbols[it.classId] = it }
    } else {
      null
    }
  }

  class ProviderCallable
  private constructor(
    val owner: FirClassSymbol<*>,
    val symbol: FirCallableSymbol<*>,
    val instanceReceiver: ConeClassLikeType?,
    val valueParameters: List<FirValueParameterSymbol>,
  ) {
    val name = symbol.name
    val shouldGenerateObject by unsafeLazy {
      instanceReceiver == null && (isProperty || valueParameters.isEmpty())
    }
    val isProperty
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

    companion object {
      fun create(owner: FirClassSymbol<*>, symbol: FirCallableSymbol<*>): ProviderCallable? {
        val instanceReceiver = if (owner.isCompanion) null else owner.defaultType()
        val params =
          when (symbol) {
            is FirPropertySymbol -> emptyList()
            is FirNamedFunctionSymbol -> symbol.valueParameterSymbols
            else -> return null
          }
        return ProviderCallable(owner, symbol, instanceReceiver, params)
      }
    }
  }
}
