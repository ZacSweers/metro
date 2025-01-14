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

import dev.zacsweers.lattice.compiler.LatticeLogger
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.extensions.ExperimentalSupertypesGenerationApi
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class LoggingFirDeclarationGenerationExtension(
  session: FirSession,
  private val logger: LatticeLogger,
  private val delegate: FirDeclarationGenerationExtension,
) : FirDeclarationGenerationExtension(session) {

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val constructors = delegate.generateConstructors(context)
    if (constructors.isEmpty()) {
      logger.log { "generateConstructors: generated no constructors for ${context.owner.classId}" }
    } else {
      logger.log {
        "generateConstructors: ${constructors.size} constructors for ${context.owner.classId}"
      }
    }
    return constructors
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val functions = delegate.generateFunctions(callableId, context)
    if (functions.isEmpty()) {
      logger.log { "[generateFunctions] generated no functions for $callableId" }
    } else {
      logger.log {
        "[generateFunctions] ${functions.size} functions for $callableId: ${functions.joinToString { it.name.asString() }}"
      }
    }
    return functions
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    val nestedClass = delegate.generateNestedClassLikeDeclaration(owner, name, context)
    if (nestedClass == null) {
      logger.log { "[generateNestedClassLikeDeclaration] generated no class for $name" }
    } else {
      logger.log { "[generateNestedClassLikeDeclaration] generated ${nestedClass.classId}" }
    }
    return nestedClass
  }

  override fun generateProperties(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirPropertySymbol> {
    val properties = delegate.generateProperties(callableId, context)
    if (properties.isEmpty()) {
      logger.log { "[generateProperties] generated no properties for $callableId" }
    } else {
      logger.log {
        "[generateProperties] ${properties.size} properties for $callableId: ${properties.joinToString()}"
      }
    }
    return properties
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    val topLevelClass = delegate.generateTopLevelClassLikeDeclaration(classId)
    if (topLevelClass == null) {
      logger.log {
        "[generateTopLevelClassLikeDeclaration] generated no top-level class for $classId"
      }
    } else {
      logger.log {
        "[generateTopLevelClassLikeDeclaration] generated top-level ${topLevelClass.classId}"
      }
    }
    return topLevelClass
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    val names = delegate.getCallableNamesForClass(classSymbol, context)
    if (names.isEmpty()) {
      logger.log {
        "[getCallableNamesForClass] generated no callable names for ${classSymbol.classId}"
      }
    } else {
      logger.log {
        "[getCallableNamesForClass] ${names.size} callable names for ${classSymbol.classId}: ${names.joinToString()}"
      }
    }
    return names
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    val names = delegate.getNestedClassifiersNames(classSymbol, context)
    if (names.isEmpty()) {
      logger.log {
        "[getNestedClassifiersNames] generated no nested classifiers names for ${classSymbol.classId}"
      }
    } else {
      logger.log {
        "[getNestedClassifiersNames] ${names.size} nested classifiers names for ${classSymbol.classId}: ${names.joinToString()}"
      }
    }
    return names
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelCallableIds(): Set<CallableId> {
    val callableIds = delegate.getTopLevelCallableIds()
    if (callableIds.isEmpty()) {
      logger.log { "[getTopLevelCallableIds] generated no top-level callable ids" }
    } else {
      logger.log {
        "[getTopLevelCallableIds] ${callableIds.size} top-level callable ids: ${callableIds.joinToString()}"
      }
    }
    return callableIds
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    val classIds = delegate.getTopLevelClassIds()
    if (classIds.isEmpty()) {
      logger.log { "[getTopLevelClassIds] generated no top-level class ids" }
    } else {
      logger.log {
        "[getTopLevelClassIds] ${classIds.size} top-level class ids: ${classIds.joinToString()}"
      }
    }
    return classIds
  }

  override fun hasPackage(packageFqName: FqName): Boolean {
    val hasPackage = delegate.hasPackage(packageFqName)
    logger.log { "[hasPackage] $packageFqName: $hasPackage" }
    return hasPackage
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    val registrar = this
    with(delegate) { registrar.registerPredicates() }
  }
}

internal class LoggingFirSupertypeGenerationExtension(
  session: FirSession,
  private val logger: LatticeLogger,
  private val delegate: FirSupertypeGenerationExtension,
) : FirSupertypeGenerationExtension(session) {
  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    val needsTransform = delegate.needTransformSupertypes(declaration)
    logger.log { "needTransformSupertypes: $needsTransform for ${declaration.classId}" }
    return needsTransform
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    val additionalSupertypes =
      delegate.computeAdditionalSupertypes(classLikeDeclaration, resolvedSupertypes, typeResolver)
    logger.log {
      "computeAdditionalSupertypes: ${additionalSupertypes.size} additional supertypes for ${classLikeDeclaration.classId}: ${additionalSupertypes.joinToString { it.classId!!.asString() }}"
    }
    return additionalSupertypes
  }

  @ExperimentalSupertypesGenerationApi
  override fun computeAdditionalSupertypesForGeneratedNestedClass(
    klass: FirRegularClass,
    typeResolver: TypeResolveService
  ): List<FirResolvedTypeRef> {
    val additionalSupertypes =
      delegate.computeAdditionalSupertypesForGeneratedNestedClass(klass, typeResolver)
    logger.log {
      "computeAdditionalSupertypesForGeneratedNestedClass: ${additionalSupertypes.size} additional supertypes for nested ${klass.classId}: ${additionalSupertypes.joinToString { it.coneType.classId!!.asString() }}"
    }
    return additionalSupertypes
  }
}

// For testing lifecycles
internal class EmptyFirSupertypeGenerationExtension(session: FirSession) :
  FirSupertypeGenerationExtension(session) {
  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    return true
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    return emptyList()
  }
}
