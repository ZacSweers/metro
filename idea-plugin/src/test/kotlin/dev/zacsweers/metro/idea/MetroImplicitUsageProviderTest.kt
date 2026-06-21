// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.idea.k2.codeinsight.inspections.UnusedSymbolInspection
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile

class MetroImplicitUsageProviderTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    setMetroEnabled(null)
    module.addMetroRuntimeLibrary()
  }

  fun testMarksMetroDeclarationsAsImplicitlyUsed() {
    val declarations = kotlinFileDeclarations()

    assertTrue(declarations.function("bindService").isMetroImplicitUsage())
    assertTrue(declarations.function("provideService").isMetroImplicitUsage())
    assertTrue(declarations.property("providedProperty").isMetroImplicitUsage())
    assertTrue(declarations.property("getterProvidedProperty").isMetroImplicitUsage())
    assertTrue(declarations.function("multibindsServices").isMetroImplicitUsage())
    assertTrue(declarations.parameter("providedInstance").isMetroImplicitUsage())
    assertTrue(declarations.klass("InjectedService").isMetroImplicitUsage())
    assertTrue(declarations.klass("InjectedService").primaryConstructor!!.isMetroImplicitUsage())
    assertTrue(declarations.function("functionInject").isMetroImplicitUsage())
    assertTrue(declarations.klass("AssistedInjectedService").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedBindingService").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedSetService").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedMapService").isMetroImplicitUsage())
    assertTrue(
      declarations
        .klass("ConstructorAssistedInjectedService")
        .primaryConstructor!!
        .isMetroImplicitUsage()
    )
  }

  fun testMarksCustomMetroDeclarationsAsImplicitlyUsedWhenConfigured() {
    setMetroOptions(
      "custom-binds" to "test/CustomBinds",
      "custom-contributes-binding" to "test/CustomContributesBinding",
      "custom-contributes-into-set" to "test/CustomContributesIntoCollection",
      "custom-elements-into-set" to "test/CustomContributesIntoSet",
      "custom-provides" to "test/CustomProvides",
      "custom-into-map" to "test/CustomContributesIntoMap",
      "custom-multibinds" to "test/CustomMultibinds",
      "custom-inject" to "test/CustomInject",
      "custom-assisted-inject" to "test/CustomAssistedInject",
    )

    val declarations = kotlinFileDeclarations()

    assertTrue(declarations.function("customBindService").isMetroImplicitUsage())
    assertTrue(declarations.function("customProvideService").isMetroImplicitUsage())
    assertTrue(declarations.property("customProvidedProperty").isMetroImplicitUsage())
    assertTrue(declarations.property("customGetterProvidedProperty").isMetroImplicitUsage())
    assertTrue(declarations.function("customMultibindsServices").isMetroImplicitUsage())
    assertTrue(declarations.parameter("customProvidedInstance").isMetroImplicitUsage())
    assertTrue(declarations.klass("CustomInjectedService").isMetroImplicitUsage())
    assertTrue(
      declarations.klass("CustomInjectedService").primaryConstructor!!.isMetroImplicitUsage()
    )
    assertTrue(declarations.function("customFunctionInject").isMetroImplicitUsage())
    assertTrue(declarations.klass("CustomAssistedInjectedService").isMetroImplicitUsage())
    assertTrue(declarations.klass("CustomContributedBindingService").isMetroImplicitUsage())
    assertTrue(declarations.klass("CustomContributedSetService").isMetroImplicitUsage())
    assertTrue(declarations.klass("CustomContributedMapService").isMetroImplicitUsage())
    assertTrue(declarations.klass("CustomContributedCollectionService").isMetroImplicitUsage())
    assertTrue(
      declarations
        .klass("CustomConstructorAssistedInjectedService")
        .primaryConstructor!!
        .isMetroImplicitUsage()
    )
  }

  fun testDoesNotMarkCustomMetroDeclarationsAsImplicitlyUsedWithoutOptions() {
    val declarations = kotlinFileDeclarations()

    assertFalse(declarations.function("customBindService").isMetroImplicitUsage())
    assertFalse(declarations.function("customProvideService").isMetroImplicitUsage())
    assertFalse(declarations.function("customMultibindsServices").isMetroImplicitUsage())
    assertFalse(declarations.klass("CustomInjectedService").isMetroImplicitUsage())
    assertFalse(declarations.function("customFunctionInject").isMetroImplicitUsage())
    assertFalse(declarations.klass("CustomAssistedInjectedService").isMetroImplicitUsage())
    assertFalse(declarations.klass("CustomContributedBindingService").isMetroImplicitUsage())
  }

  fun testMarksContributionProviderDeclarationsAsImplicitlyUsedWhenConfigured() {
    setMetroOptions(
      "contributes-as-inject" to "false",
      "generate-contribution-providers" to "true",
    )

    val declarations = kotlinFileDeclarations()

    assertTrue(declarations.klass("ContributedBindingService").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedSetService").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedMapService").isMetroImplicitUsage())
    assertFalse(declarations.klass("ExposedContributedBindingService").isMetroImplicitUsage())
  }

  fun testMarksDaggerInteropDeclarationsAsImplicitlyUsedWhenConfigured() {
    setMetroOptions("interop-include-dagger-annotations" to "true")
    myFixture.addFileToProject(
      "dagger/Annotations.kt",
      """
      package dagger

      annotation class Binds
      annotation class BindsInstance
      annotation class Provides
      """
        .trimIndent(),
    )
    myFixture.addFileToProject(
      "dagger/assisted/Annotations.kt",
      """
      package dagger.assisted

      annotation class AssistedInject
      """
        .trimIndent(),
    )
    myFixture.addFileToProject(
      "dagger/multibindings/Annotations.kt",
      """
      package dagger.multibindings

      annotation class Multibinds
      """
        .trimIndent(),
    )

    val file =
      myFixture.configureByText(
        "DaggerTest.kt",
        """
        package test

        import dagger.Binds
        import dagger.Provides
        import dagger.assisted.AssistedInject
        import dagger.multibindings.Multibinds

        interface Service
        class ServiceImpl : Service

        interface DaggerModule {
          @Binds fun daggerBindService(impl: ServiceImpl): Service
          @Provides fun daggerProvideService(): Service = ServiceImpl()
          @Multibinds fun daggerMultibindsServices(): Set<Service>
        }

        class DaggerAssistedInjectedService @AssistedInject constructor(service: Service)
        """
          .trimIndent(),
      ) as KtFile
    val declarations = file.declarationsIncludingNested()

    assertTrue(declarations.function("daggerBindService").isMetroImplicitUsage())
    assertTrue(declarations.function("daggerProvideService").isMetroImplicitUsage())
    assertTrue(declarations.function("daggerMultibindsServices").isMetroImplicitUsage())
    assertTrue(declarations.klass("DaggerAssistedInjectedService").isMetroImplicitUsage())
  }

  fun testMarksCircuitInjectDeclarationsAsImplicitlyUsedWhenEnabled() {
    setMetroOptions("enable-circuit-codegen" to "true")

    val declarations = circuitFileDeclarations()

    assertTrue(declarations.function("circuitPresenter").isMetroImplicitUsage())
    assertTrue(declarations.klass("CircuitUiClass").isMetroImplicitUsage())
  }

  fun testDoesNotMarkCircuitInjectDeclarationsAsImplicitlyUsedWithoutOption() {
    val declarations = circuitFileDeclarations()

    assertFalse(declarations.function("circuitPresenter").isMetroImplicitUsage())
    assertFalse(declarations.klass("CircuitUiClass").isMetroImplicitUsage())
  }

  private fun circuitFileDeclarations(): List<KtDeclaration> {
    myFixture.addFileToProject(
      "circuit/CircuitInject.kt",
      """
      package com.slack.circuit.codegen.annotations

      import kotlin.reflect.KClass

      annotation class CircuitInject(val screen: KClass<*>, val scope: KClass<*>)
      """
        .trimIndent(),
    )
    val file =
      myFixture.configureByText(
        "CircuitTest.kt",
        """
        package test

        import com.slack.circuit.codegen.annotations.CircuitInject

        object AppScope
        object HomeScreen

        @CircuitInject(HomeScreen::class, AppScope::class)
        fun circuitPresenter(): Int = 0

        @CircuitInject(HomeScreen::class, AppScope::class)
        class CircuitUiClass
        """
          .trimIndent(),
      ) as KtFile
    return file.declarationsIncludingNested()
  }

  fun testDoesNotMarkMetroDeclarationsWhenSuppressionSettingIsDisabled() {
    val settings = MetroSettings.getInstance(project).state
    settings.suppressUnusedWarnings = false
    try {
      val declarations = kotlinFileDeclarations()
      assertFalse(declarations.function("bindService").isMetroImplicitUsage())
      assertFalse(declarations.klass("InjectedService").isMetroImplicitUsage())
    } finally {
      settings.suppressUnusedWarnings = true
    }
  }

  fun testDoesNotMarkUnsupportedDeclarationsAsImplicitlyUsed() {
    val declarations = kotlinFileDeclarations()

    assertFalse(declarations.function("unusedFunction").isMetroImplicitUsage())
    assertFalse(declarations.klass("ClassAnnotatedInject").isMetroImplicitUsage())
    assertFalse(declarations.property("memberInject").isMetroImplicitUsage())
  }

  fun testDoesNotMarkMetroDeclarationsAsImplicitlyUsedWhenMetroIsDisabled() {
    setMetroEnabled(false)

    val declarations = kotlinFileDeclarations()

    assertFalse(declarations.function("bindService").isMetroImplicitUsage())
    assertFalse(declarations.function("provideService").isMetroImplicitUsage())
    assertFalse(declarations.function("multibindsServices").isMetroImplicitUsage())
    assertFalse(declarations.klass("InjectedService").isMetroImplicitUsage())
    assertFalse(declarations.function("functionInject").isMetroImplicitUsage())
    assertFalse(declarations.klass("ContributedBindingService").isMetroImplicitUsage())
  }

  fun testUnusedDeclarationSuppressorRespectsMetroEnabledState() {
    val declarations = kotlinFileDeclarations()
    val suppressor = MetroUnusedDeclarationInspectionSuppressor()
    val bindService = declarations.function("bindService")

    assertTrue(suppressor.isSuppressedFor(bindService, "unused"))

    setMetroEnabled(false)

    assertFalse(suppressor.isSuppressedFor(bindService, "unused"))
  }

  fun testUnusedDeclarationHighlightingRespectsMetroImplicitUsage() {
    myFixture.enableInspections(UnusedSymbolInspection())
    configureMetroFile()

    val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
    val warningText = warnings.joinToString("\n") { "${it.text}: ${it.description}" }
    val warningDescriptions = warnings.map { it.description }.toSet()

    assertFalse("bindService should not be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Function "bindService" is never used""")
    }
    assertFalse("provideService should not be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Function "provideService" is never used""")
    }
    assertFalse("multibindsServices should not be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Function "multibindsServices" is never used""")
    }
    assertFalse("InjectedService should not be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Class "InjectedService" is never used""")
    }
    assertFalse("functionInject should not be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Function "functionInject" is never used""")
    }
    assertTrue("unusedFunction should still be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Function "unusedFunction" is never used""")
    }
  }

  fun testUnusedDeclarationHighlightingRespectsSecondaryInjectConstructors() {
    myFixture.enableInspections(UnusedSymbolInspection())
    myFixture.configureByText(
      "Ctors.kt",
      """
      package test

      import dev.zacsweers.metro.Inject

      class Repository(val name: String) {
        @Inject
        constructor(count: Int) : this(count.toString())
      }

      fun useRepository(): Repository = Repository("direct")
      """
        .trimIndent(),
    )

    val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
    val warningText = warnings.joinToString("\n") { "${it.text}: ${it.description}" }
    assertFalse("Secondary @Inject constructor should not be reported as unused:\n$warningText") {
      warnings.any { it.description.orEmpty().contains("onstructor") }
    }
  }

  fun testUnusedDeclarationHighlightingRespectsCircuitInjectWhenEnabled() {
    setMetroOptions("enable-circuit-codegen" to "true")
    myFixture.enableInspections(UnusedSymbolInspection())
    val declarations = circuitFileDeclarations()
    myFixture.configureFromExistingVirtualFile(declarations.first().containingFile.virtualFile)

    val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
    val warningText = warnings.joinToString("\n") { "${it.text}: ${it.description}" }
    val descriptions = warnings.map { it.description }.toSet()

    assertFalse("circuitPresenter should not be reported as unused:\n$warningText") {
      descriptions.contains("""Function "circuitPresenter" is never used""")
    }
    assertFalse("CircuitUiClass should not be reported as unused:\n$warningText") {
      descriptions.contains("""Class "CircuitUiClass" is never used""")
    }
  }

  private fun kotlinFileDeclarations(): List<KtDeclaration> {
    return configureMetroFile().declarationsIncludingNested()
  }

  private fun configureMetroFile(): KtFile {
    return myFixture.configureByText(
      "Test.kt",
      """
      package test

      import dev.zacsweers.metro.Binds
      import dev.zacsweers.metro.Inject
      import dev.zacsweers.metro.Multibinds
      import dev.zacsweers.metro.Provides
      import dev.zacsweers.metro.AssistedInject
      import dev.zacsweers.metro.ContributesBinding
      import dev.zacsweers.metro.ContributesIntoMap
      import dev.zacsweers.metro.ContributesIntoSet
      import dev.zacsweers.metro.ExperimentalMetroApi
      import dev.zacsweers.metro.ExposeImplBinding

      annotation class CustomAssistedInject
      annotation class CustomBinds
      annotation class CustomContributesBinding
      annotation class CustomContributesIntoCollection
      annotation class CustomContributesIntoMap
      annotation class CustomContributesIntoSet
      annotation class CustomInject
      annotation class CustomMultibinds
      annotation class CustomProvides
      object AppScope
      interface Service
      class ServiceImpl : Service

      interface Module {
        @Binds fun bindService(impl: ServiceImpl): Service
        @Provides fun provideService(): Service = ServiceImpl()
        @Provides val providedProperty: Service get() = ServiceImpl()
        val getterProvidedProperty: Service
          @Provides get() = ServiceImpl()
        @Multibinds fun multibindsServices(): Set<Service>
      }

      interface Factory {
        fun create(@Provides providedInstance: Service): Service
      }

      class InjectedService @Inject constructor(service: Service)
      @AssistedInject class AssistedInjectedService(service: Service)
      class ConstructorAssistedInjectedService @AssistedInject constructor(service: Service)
      @ContributesBinding(AppScope::class) class ContributedBindingService : Service
      @ContributesIntoSet(AppScope::class) class ContributedSetService : Service
      @ContributesIntoMap(AppScope::class) class ContributedMapService : Service
      @OptIn(ExperimentalMetroApi::class)
      @ExposeImplBinding
      @ContributesBinding(AppScope::class)
      class ExposedContributedBindingService : Service

      @Inject class ClassAnnotatedInject(service: Service)

      class MemberInjectedService {
        @Inject lateinit var memberInject: Service
        @Inject fun functionInject(service: Service) = Unit
      }

      interface CustomModule {
        @CustomBinds fun customBindService(impl: ServiceImpl): Service
        @CustomProvides fun customProvideService(): Service = ServiceImpl()
        @CustomProvides val customProvidedProperty: Service get() = ServiceImpl()
        val customGetterProvidedProperty: Service
          @CustomProvides get() = ServiceImpl()
        @CustomMultibinds fun customMultibindsServices(): Set<Service>
      }

      interface CustomFactory {
        fun create(@CustomProvides customProvidedInstance: Service): Service
      }

      class CustomInjectedService @CustomInject constructor(service: Service)
      @CustomAssistedInject class CustomAssistedInjectedService(service: Service)
      class CustomConstructorAssistedInjectedService @CustomAssistedInject constructor(
        service: Service
      )
      @CustomContributesBinding class CustomContributedBindingService : Service
      @CustomContributesIntoSet class CustomContributedSetService : Service
      @CustomContributesIntoMap class CustomContributedMapService : Service
      @CustomContributesIntoCollection class CustomContributedCollectionService : Service

      class CustomMemberInjectedService {
        @CustomInject fun customFunctionInject(service: Service) = Unit
      }

      fun unusedFunction() = Unit
      """
        .trimIndent(),
    ) as KtFile
  }

  private fun setMetroEnabled(enabled: Boolean?) {
    if (enabled == null) {
      project.setMetroOptions()
    } else {
      project.setMetroOptions("enabled" to enabled.toString())
    }
  }

  private fun setMetroOptions(vararg options: Pair<String, String>) {
    project.setMetroOptions(*options)
  }
}
