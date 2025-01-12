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
import dev.zacsweers.lattice.compiler.fir.isDependencyGraph
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.name.ClassId

// Toe-hold for contributed types
internal class ContributedInterfaceSupertypeGenerator(session: FirSession) :
  FirSupertypeGenerationExtension(session) {

  private val predicate =
    LookupPredicate.create {
      annotated(session.latticeClassIds.allContributesAnnotations.map(ClassId::asSingleFqName))
    }

  // NOTE this is only in-compilation
  private val contributingClasses by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(predicate)
      .filterIsInstance<FirRegularClassSymbol>()
  }

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration) =
    declaration.symbol.isDependencyGraph(session)

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    val contributedTypes = mutableListOf<ConeKotlinType>()
    // TODO cache these by scopes?
    val generatedNames =
      session.symbolProvider.symbolNamesProvider
        .getTopLevelClassifierNamesInPackage(LatticeSymbols.FqNames.latticeHintsPackage)
        .orEmpty()
        .mapNotNull { name ->
          session.symbolProvider.getClassLikeSymbolByClassId(
            ClassId(LatticeSymbols.FqNames.latticeHintsPackage, name)
          )
        }

    // TODO identify contributed scope, source scope

    return contributedTypes
  }
}
