// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.asFqNames
import dev.zacsweers.metro.compiler.symbols.DaggerSymbols
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.parentAnnotated

internal class ExtensionPredicates(private val classIds: ClassIds) {

  // Lets us register and resolve any annotations that are qualifiers
  internal val qualifiersPredicate =
    DeclarationPredicate.create {
      metaAnnotated(classIds.qualifierAnnotations.asFqNames(), includeItself = false)
    }
  internal val scopesPredicate =
    DeclarationPredicate.create {
      metaAnnotated(classIds.scopeAnnotations.asFqNames(), includeItself = false)
    }
  internal val mapKeysPredicate =
    DeclarationPredicate.create {
      metaAnnotated(classIds.mapKeyAnnotations.asFqNames(), includeItself = false)
    }

  internal val bindingContainerPredicate =
    annotated(classIds.bindingContainerAnnotations.asFqNames())
  internal val originPredicate = annotated(classIds.originAnnotations.asFqNames())
  internal val dependencyGraphPredicate = annotated(classIds.dependencyGraphAnnotations.asFqNames())
  internal val graphExtensionFactoryPredicate =
    annotated(classIds.graphExtensionFactoryAnnotations.asFqNames())

  internal val dependencyGraphAndFactoryPredicate =
    annotated(
      (classIds.dependencyGraphAnnotations + classIds.dependencyGraphFactoryAnnotations).asFqNames()
    )

  internal val dependencyGraphCompanionPredicate =
    parentAnnotated(classIds.dependencyGraphAnnotations.asFqNames())

  internal val contributesAnnotationPredicate =
    annotated(classIds.allContributesAnnotations.asFqNames())

  internal val providesAnnotationPredicate = annotated(classIds.providesAnnotations.asFqNames())

  internal val injectAnnotationPredicate = annotated(classIds.injectAnnotations.asFqNames())

  internal val injectLikeAnnotationsPredicate =
    annotated(classIds.injectLikeAnnotations.asFqNames())

  internal val assistedAnnotationPredicate = annotated(classIds.assistedAnnotations.asFqNames())

  internal val assistedFactoryAnnotationPredicate =
    annotated(classIds.assistedFactoryAnnotations.asFqNames())

  internal val bindsAnnotationPredicate = annotated(classIds.bindsAnnotations.asFqNames())

  internal val multibindsAnnotationPredicate = annotated(classIds.multibindsAnnotations.asFqNames())

  internal val bindsOptionalOfAnnotationPredicate =
    annotated(DaggerSymbols.ClassIds.DAGGER_BINDS_OPTIONAL_OF.asSingleFqName())
}
