// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.compat.CompatContext
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
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull

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
public sealed interface MetroFirTypeResolver {
  public fun resolveType(typeRef: FirTypeRef): ConeKotlinType

  public interface Factory {
    public fun create(classSymbol: FirClassLikeSymbol<*>): MetroFirTypeResolver?

    context(compatContext: CompatContext)
    public fun create(functionSymbol: FirFunctionSymbol<*>): MetroFirTypeResolver?

    public companion object {
      internal operator fun invoke(session: FirSession): Factory = FactoryImpl(session)
    }
  }

  private class FactoryImpl(private val session: FirSession) : Factory {
    private val scopeSession = ScopeSession()
    private val resolversByFile = mutableMapOf<FirFile, LocalMetroFirTypeResolver?>()
    private val externalResolver by lazy { ExternalMetroFirTypeResolver(session) }
    private val allSessions: List<FirSession> by lazy { session.allSessions }

    override fun create(classSymbol: FirClassLikeSymbol<*>): MetroFirTypeResolver? {
      if (classSymbol.origin !is FirDeclarationOrigin.Source) return externalResolver
      // Look up through all firProviders as we may be a KMP compilation
      // The implementation of getFirClassifierContainerFileIfAny is an O(1) lookup in its impl in
      // FirProviderImpl
      val file: FirFile =
        allSessions.firstNotNullOfOrNull {
          it.firProvider.getFirClassifierContainerFileIfAny(classSymbol)
        } ?: return null
      return create(file)
    }

    context(compatContext: CompatContext)
    override fun create(functionSymbol: FirFunctionSymbol<*>): MetroFirTypeResolver? {
      if (functionSymbol.origin !is FirDeclarationOrigin.Source) {
        return ExternalMetroFirTypeResolver(session)
      }

      // if it's not top-level, create in the class instead
      val enclosingClass = with(compatContext) { functionSymbol.getContainingClassSymbol() }
      if (enclosingClass != null) return create(enclosingClass)

      // Look up through all firProviders as we may be a KMP compilation
      val file: FirFile =
        allSessions.firstNotNullOfOrNull {
          it.firProvider.getFirCallableContainerFile(functionSymbol)
        } ?: return null
      return create(file)
    }

    private fun create(file: FirFile): MetroFirTypeResolver? {
      return resolversByFile.getOrPut(file) {
        val scopes = createImportingScopes(file, session, scopeSession)
        val configuration = TypeResolutionConfiguration(scopes, emptyList(), useSiteFile = file)
        LocalMetroFirTypeResolver(session, configuration)
      }
    }
  }

  private class ExternalMetroFirTypeResolver(private val session: FirSession) :
    MetroFirTypeResolver {

    override fun resolveType(typeRef: FirTypeRef): ConeKotlinType {
      check(typeRef is FirUserTypeRef)
      // Try the already-resolved type first, but fall back to actual resolution for cases where
      // the type ref is freshly constructed and unresolved (e.g., during SUPERTYPES phase in
      // Kotlin 2.3.20+).
      typeRef.coneTypeOrNull?.let {
        return it
      }
      return session.typeResolver
        .resolveType(
          typeRef = typeRef,
          configuration = EMPTY_CONFIGURATION,
          areBareTypesAllowed = true,
          isOperandOfIsOperator = false,
          resolveDeprecations = false,
          supertypeSupplier = SupertypeSupplier.Default,
          expandTypeAliases = false,
        )
        .type
    }
  }

  private class LocalMetroFirTypeResolver(
    private val session: FirSession,
    private val configuration: TypeResolutionConfiguration,
  ) : MetroFirTypeResolver {
    override fun resolveType(typeRef: FirTypeRef): ConeKotlinType {
      if (typeRef is FirResolvedTypeRef) return typeRef.coneType
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

  public companion object {
    // For cases where we use this in IR, types are already resolved so just read coneType
    internal fun forIrUse(): MetroFirTypeResolver = IrMetroFirTypeResolver

    private val EMPTY_CONFIGURATION =
      TypeResolutionConfiguration(
        scopes = emptyList(),
        containingClassDeclarations = emptyList(),
        useSiteFile = null,
      )
  }

  private object IrMetroFirTypeResolver : MetroFirTypeResolver {
    override fun resolveType(typeRef: FirTypeRef): ConeKotlinType {
      check(typeRef is FirUserTypeRef)
      return typeRef.coneType
    }
  }
}

internal fun MetroFirTypeResolver.Factory.caching(): MetroFirTypeResolver.Factory {
  return object : MetroFirTypeResolver.Factory by this {
    private val delegate = this@caching
    private val typeResolverCache = mutableMapOf<FirClassLikeSymbol<*>, Any>()

    override fun create(classSymbol: FirClassLikeSymbol<*>): MetroFirTypeResolver? {
      val cached = typeResolverCache[classSymbol]
      if (cached != null) {
        @Suppress("UNCHECKED_CAST")
        return if (cached === NULL_SENTINEL) null else cached as MetroFirTypeResolver
      }
      val computed = delegate.create(classSymbol)
      typeResolverCache[classSymbol] = computed ?: NULL_SENTINEL
      return computed
    }
  }
}

private val NULL_SENTINEL = Any()
