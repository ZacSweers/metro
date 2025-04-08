// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.asFqNames
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate

public class ExtensionPredicates(private val classIds: ClassIds) {

  // Lets us register and resolve any annotations that are qualifiers
  internal val qualifiersPredicate =
    DeclarationPredicate.create {
      metaAnnotated(classIds.qualifierAnnotations.asFqNames(), includeItself = false)
    }

  internal val dependencyGraphPredicate =
    LookupPredicate.create { annotated(classIds.dependencyGraphAnnotations.asFqNames()) }

  internal val contributingTypesPredicate =
    LookupPredicate.create { annotated(classIds.allContributesAnnotations.asFqNames()) }
}
