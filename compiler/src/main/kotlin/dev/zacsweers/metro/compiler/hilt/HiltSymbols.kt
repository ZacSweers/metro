// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.hilt

import dev.zacsweers.metro.compiler.asName
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

internal object HiltSymbols {
  private val hiltPackage = FqName("dagger.hilt")
  private val javaxInjectPackage = FqName("javax.inject")

  /** The package Hilt's processor emits `@AggregatedDeps` markers into. */
  val aggregatedDepsPackage = FqName("hilt_aggregated_deps")

  // Annotations
  val InstallIn = ClassId(hiltPackage, "InstallIn".asName())
  val EntryPoint = ClassId(hiltPackage, "EntryPoint".asName())
  val DefineComponent = ClassId(hiltPackage, "DefineComponent".asName())
  val AggregatedDeps =
    ClassId(FqName("dagger.hilt.processor.internal.aggregateddeps"), "AggregatedDeps".asName())
  val Module = ClassId(FqName("dagger"), "Module".asName())
  val JavaxScope = ClassId(javaxInjectPackage, "Scope".asName())

  // Hilt/Dagger predicates
  val installInPredicate = annotated(InstallIn.asSingleFqName())
  val modulePredicate = annotated(Module.asSingleFqName())
  val entryPointPredicate = annotated(EntryPoint.asSingleFqName())
}

internal object HiltNames {
  val components = "components".asName()
  val modules = "modules".asName()
  val entryPoints = "entryPoints".asName()
  val componentEntryPoints = "componentEntryPoints".asName()
  val replaces = "replaces".asName()
  val test = "test".asName()
}
