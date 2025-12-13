// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * A composite [FirDeclarationGenerationExtension] that delegates to multiple extensions.
 *
 * This enables composition of FIR code generators, working around the limitation that FIR
 * generators normally cannot see each other's generated code. External extensions are processed
 * BEFORE native extensions, allowing their generated code to be consumed by Metro's generators.
 *
 * Important: This composite maintains a mapping of which extension claimed responsibility for
 * generating each declaration. When `generate*` methods are called, only the extension that
 * originally claimed the name in `get*Names` is called. This preserves the 1:1 relationship
 * between "what names I said I'd generate" and "what I'm asked to generate".
 *
 * @param session The FIR session
 * @param externalExtensions Extensions loaded via ServiceLoader (processed first)
 * @param nativeExtensions Metro's built-in generators (processed after external)
 */
internal class CompositeMetroFirDeclarationGenerationExtension(
  session: FirSession,
  private val externalExtensions: List<MetroFirDeclarationGenerationExtension>,
  private val nativeExtensions: List<FirDeclarationGenerationExtension>,
) : FirDeclarationGenerationExtension(session) {

  private val allExtensions: List<FirDeclarationGenerationExtension>
    get() = externalExtensions + nativeExtensions

  // Mapping key: (ownerClassId, name) -> set of extensions that claimed this name
  // Using sets to prevent duplicate registrations when methods are called multiple times
  private data class NestedClassKey(val ownerClassId: ClassId, val name: Name)
  private val nestedClassOwners = mutableMapOf<NestedClassKey, MutableSet<FirDeclarationGenerationExtension>>()

  private data class CallableKey(val ownerClassId: ClassId, val name: Name)
  private val callableOwners = mutableMapOf<CallableKey, MutableSet<FirDeclarationGenerationExtension>>()

  private data class TopLevelClassKey(val classId: ClassId)
  private val topLevelClassOwners = mutableMapOf<TopLevelClassKey, MutableSet<FirDeclarationGenerationExtension>>()

  private data class TopLevelCallableKey(val callableId: CallableId)
  private val topLevelCallableOwners = mutableMapOf<TopLevelCallableKey, MutableSet<FirDeclarationGenerationExtension>>()

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    for (extension in allExtensions) {
      with(extension) { registerPredicates() }
    }
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    val result = mutableSetOf<ClassId>()
    for (extension in allExtensions) {
      val ids = extension.getTopLevelClassIds()
      for (id in ids) {
        result.add(id)
        topLevelClassOwners.getOrPut(TopLevelClassKey(id)) { mutableSetOf() }.add(extension)
      }
    }
    return result
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    val owners = topLevelClassOwners[TopLevelClassKey(classId)] ?: return null
    for (extension in owners) {
      extension.generateTopLevelClassLikeDeclaration(classId)?.let { return it }
    }
    return null
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelCallableIds(): Set<CallableId> {
    val result = mutableSetOf<CallableId>()
    for (extension in allExtensions) {
      val ids = extension.getTopLevelCallableIds()
      for (id in ids) {
        result.add(id)
        topLevelCallableOwners.getOrPut(TopLevelCallableKey(id)) { mutableSetOf() }.add(extension)
      }
    }
    return result
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    val result = mutableSetOf<Name>()
    for (extension in allExtensions) {
      val names = extension.getNestedClassifiersNames(classSymbol, context)
      for (name in names) {
        result.add(name)
        nestedClassOwners.getOrPut(NestedClassKey(classSymbol.classId, name)) { mutableSetOf() }
          .add(extension)
      }
    }
    return result
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    val owners = nestedClassOwners[NestedClassKey(owner.classId, name)] ?: return null
    for (extension in owners) {
      extension.generateNestedClassLikeDeclaration(owner, name, context)?.let { return it }
    }
    return null
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    val result = mutableSetOf<Name>()
    for (extension in allExtensions) {
      val names = extension.getCallableNamesForClass(classSymbol, context)
      for (name in names) {
        result.add(name)
        callableOwners.getOrPut(CallableKey(classSymbol.classId, name)) { mutableSetOf() }
          .add(extension)
      }
    }
    return result
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    // Constructors are special - they use SpecialNames.INIT
    // Only call extensions that claimed INIT for this class
    val owners = callableOwners[CallableKey(context.owner.classId, org.jetbrains.kotlin.name.SpecialNames.INIT)]
      ?: return emptyList()
    return owners.flatMap { it.generateConstructors(context) }
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    return if (context != null) {
      // Member function
      val owners = callableOwners[CallableKey(context.owner.classId, callableId.callableName)]
        ?: return emptyList()
      owners.flatMap { it.generateFunctions(callableId, context) }
    } else {
      // Top-level function
      val owners = topLevelCallableOwners[TopLevelCallableKey(callableId)] ?: return emptyList()
      owners.flatMap { it.generateFunctions(callableId, null) }
    }
  }

  override fun generateProperties(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirPropertySymbol> {
    return if (context != null) {
      // Member property
      val owners = callableOwners[CallableKey(context.owner.classId, callableId.callableName)]
        ?: return emptyList()
      owners.flatMap { it.generateProperties(callableId, context) }
    } else {
      // Top-level property
      val owners = topLevelCallableOwners[TopLevelCallableKey(callableId)] ?: return emptyList()
      owners.flatMap { it.generateProperties(callableId, null) }
    }
  }

  override fun hasPackage(packageFqName: FqName): Boolean {
    return allExtensions.any { it.hasPackage(packageFqName) }
  }
}
