// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.anvil

import dev.zacsweers.metro.compiler.asName
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

internal object AnvilSymbols {
  private val annotationsPackage = FqName("com.squareup.anvil.annotations")

  /** The package Anvil writes classpath hints into. */
  val hintPackage = FqName("anvil.hint")

  val ContributesSubcomponent = ClassId(annotationsPackage, "ContributesSubcomponent".asName())
  val ContributesSubcomponentFactory =
    ContributesSubcomponent.createNestedClassId("Factory".asName())

  /** Marks the binding modules that Anvil generates. Its first argument is the bound class. */
  val InternalBindingMarker =
    ClassId(annotationsPackage.child("internal".asName()), "InternalBindingMarker".asName())

  val parentScope = "parentScope".asName()

  /** Suffix of the hint property whose `KClass` type names the contributed class. */
  const val REFERENCE_SUFFIX = "_reference"

  /** Anvil numbers each scope property. Anvil 2.4 and earlier wrote one without a number. */
  val scopePropertySuffix = Regex("_scope\\d*$")
}
