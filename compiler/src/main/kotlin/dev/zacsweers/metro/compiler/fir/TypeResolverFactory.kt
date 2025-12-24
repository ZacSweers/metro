// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.SupertypeSupplier
import org.jetbrains.kotlin.fir.resolve.TypeResolutionConfiguration
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.typeResolver
import org.jetbrains.kotlin.fir.scopes.createImportingScopes
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.coneType

/**
 * A custom type resolver focused on resolving [FirTypeRef] instances potentially from other files
 * in the same compilation. To do this, we manually create custom
 * [MetroFirTypeResolver.LocalMetroFirTypeResolver] instances that encapsulate a cached
 * [TypeResolutionConfiguration] that can resolve types in that file. This is particularly important
 * for `@Contributes*.replaces` resolution in contribution merging, as these class references may
 * otherwise not be resolvable otherwise if they are in the same compilation but a different file
 * from the graph.
 *
 * For external origins, this just looks up the type ref [ConeKotlinType] directly since it is
 * already resolved.
 */
internal sealed interface MetroFirTypeResolver {
  fun resolveType(typeRef: FirTypeRef): ConeKotlinType

  class Factory(private val session: FirSession, private val allSessions: Sequence<FirSession>) {
    private val scopeSession = ScopeSession()
    private val resolversByFile = mutableMapOf<FirFile, LocalMetroFirTypeResolver?>()

    fun create(classSymbol: FirClassLikeSymbol<*>): MetroFirTypeResolver? {
      if (classSymbol.origin !is FirDeclarationOrigin.Source) return ExternalMetroFirTypeResolver
      // Look up through all firProviders as we may be a KMP compilation
      // The implementation of getFirClassifierContainerFileIfAny is an O(1) lookup in its impl in
      // FirProviderImpl
      val file: FirFile =
        allSessions.firstNotNullOfOrNull {
          it.firProvider.getFirClassifierContainerFileIfAny(classSymbol)
        } ?: return null
      return resolversByFile.getOrPut(file) {
        val scopes = createImportingScopes(file, session, scopeSession)
        val configuration = TypeResolutionConfiguration(scopes, emptyList(), useSiteFile = file)
        LocalMetroFirTypeResolver(session, configuration)
      }
    }
  }

  private object ExternalMetroFirTypeResolver : MetroFirTypeResolver {
    override fun resolveType(typeRef: FirTypeRef): ConeKotlinType {
      check(typeRef is FirUserTypeRef)
      return typeRef.coneType
    }
  }

  private class LocalMetroFirTypeResolver(
    private val session: FirSession,
    private val configuration: TypeResolutionConfiguration,
  ) : MetroFirTypeResolver {
    override fun resolveType(typeRef: FirTypeRef): ConeKotlinType {
      return session.typeResolver
        .resolveType(
          typeRef = typeRef,
          configuration = configuration,
          areBareTypesAllowed = true,
          isOperandOfIsOperator = false,
          resolveDeprecations = false,
          supertypeSupplier = SupertypeSupplier.Default,
          expandTypeAliases = false,
        )
        .type
    }
  }

  companion object {
    // For cases where we use this in IR, just use the external resolver
    fun forIrUse(): MetroFirTypeResolver = ExternalMetroFirTypeResolver
  }
}
