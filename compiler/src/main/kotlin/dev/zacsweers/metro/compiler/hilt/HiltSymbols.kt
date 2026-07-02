// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.hilt

import dev.zacsweers.metro.compiler.asName
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

internal object HiltSymbols {
  private val hiltPackage = FqName("dagger.hilt")
  private val hiltAndroidComponentsPackage = FqName("dagger.hilt.android.components")
  private val hiltAndroidScopesPackage = FqName("dagger.hilt.android.scopes")
  private val hiltComponentsPackage = FqName("dagger.hilt.components")
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

  // Standard Android Hilt components
  val SingletonComponent = ClassId(hiltComponentsPackage, "SingletonComponent".asName())
  val ActivityRetainedComponent =
    ClassId(hiltAndroidComponentsPackage, "ActivityRetainedComponent".asName())
  val ActivityComponent = ClassId(hiltAndroidComponentsPackage, "ActivityComponent".asName())
  val ViewModelComponent = ClassId(hiltAndroidComponentsPackage, "ViewModelComponent".asName())
  val FragmentComponent = ClassId(hiltAndroidComponentsPackage, "FragmentComponent".asName())
  val ServiceComponent = ClassId(hiltAndroidComponentsPackage, "ServiceComponent".asName())
  val ViewComponent = ClassId(hiltAndroidComponentsPackage, "ViewComponent".asName())
  val ViewWithFragmentComponent =
    ClassId(hiltAndroidComponentsPackage, "ViewWithFragmentComponent".asName())

  // Hilt/Dagger predicates
  val installInPredicate = annotated(InstallIn.asSingleFqName())
  val modulePredicate = annotated(Module.asSingleFqName())
  val entryPointPredicate = annotated(EntryPoint.asSingleFqName())
  val Singleton = ClassId(javaxInjectPackage, "Singleton".asName())
  val ActivityRetainedScoped = ClassId(hiltAndroidScopesPackage, "ActivityRetainedScoped".asName())
  val ActivityScoped = ClassId(hiltAndroidScopesPackage, "ActivityScoped".asName())
  val ViewModelScoped = ClassId(hiltAndroidScopesPackage, "ViewModelScoped".asName())
  val FragmentScoped = ClassId(hiltAndroidScopesPackage, "FragmentScoped".asName())
  val ServiceScoped = ClassId(hiltAndroidScopesPackage, "ServiceScoped".asName())
  val ViewScoped = ClassId(hiltAndroidScopesPackage, "ViewScoped".asName())
}

internal object HiltNames {
  val components = "components".asName()
  val modules = "modules".asName()
  val entryPoints = "entryPoints".asName()
  val componentEntryPoints = "componentEntryPoints".asName()
  val replaces = "replaces".asName()
  val test = "test".asName()
}
