// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.EnumSet
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/**
 * Generates mirror class declarations for `@Binds` and `@Multibinds`-annotated members, as well as
 * `@DefaultBinding`-annotated classes.
 */
internal class BindingMirrorClassFirGenerator(session: FirSession, compatContext: CompatContext) :
  FirDeclarationGenerationExtension(session), CompatContext by compatContext {

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(session.predicates.bindsAnnotationPredicate)
    register(session.predicates.multibindsAnnotationPredicate)
    register(session.predicates.bindsOptionalOfAnnotationPredicate)
    register(session.predicates.defaultBindingAnnotationPredicate)
  }

  // TODO probably not needed?
  private val mirrorClassesToGenerate = mutableSetOf<ClassId>()

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    // Only generate constructor for the mirror class
    return if (classSymbol.classId in mirrorClassesToGenerate) {
      setOf(SpecialNames.INIT)
    } else {
      emptySet()
    }
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    return if (context.owner.classId in mirrorClassesToGenerate) {
      // Private constructor to prevent instantiation
      listOf(createDefaultPrivateConstructor(context.owner, Keys.Default).symbol)
    } else {
      emptyList()
    }
  }

  @OptIn(DirectDeclarationsAccess::class)
  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    val result = mutableSetOf<Name>()

    // Check if this class has any @Binds or @Multibinds members or is a contribution class decl
    val hasBindingMembers =
      classSymbol.declarationSymbols.filterIsInstance<FirCallableSymbol<*>>().any { callable ->
        val annotations =
          callable.metroAnnotations(
            session,
            kinds =
              EnumSet.of(
                MetroAnnotations.Kind.Binds,
                MetroAnnotations.Kind.Multibinds,
                MetroAnnotations.Kind.BindsOptionalOf,
              ),
          )
        annotations.isBinds || annotations.isMultibinds || annotations.isBindsOptionalOf
      }

    if (hasBindingMembers) {
      mirrorClassesToGenerate.add(
        classSymbol.classId.createNestedClassId(Symbols.Names.BindsMirrorClass)
      )
      result += Symbols.Names.BindsMirrorClass
    }

    // Check if this class has @DefaultBinding annotation
    if (classSymbol.isAnnotatedWithAny(session, setOf(session.classIds.defaultBindingAnnotation))) {
      mirrorClassesToGenerate +=
        classSymbol.classId.createNestedClassId(Symbols.Names.DefaultBindingMirrorClass)
      result += Symbols.Names.DefaultBindingMirrorClass
    }

    return result
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    return when (name) {
      Symbols.Names.BindsMirrorClass -> {
        createNestedClass(owner, name, Keys.BindingMirrorClassDeclaration) {
            modality = Modality.ABSTRACT
          }
          .apply { markAsDeprecatedHidden(session) }
          .symbol
      }
      Symbols.Names.DefaultBindingMirrorClass -> {
        createNestedClass(owner, name, Keys.DefaultBindingMirrorClassDeclaration) {
            modality = Modality.ABSTRACT
          }
          .apply { markAsDeprecatedHidden(session) }
          .symbol
      }
      else -> null
    }
  }
}
