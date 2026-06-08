// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.k2.codeinsight.inspections.UnusedSymbolInspection
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

class MetroImplicitUsageProviderTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    setMetroEnabled(null)
    addMetroRuntimeLibrary()
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

  private fun addMetroRuntimeLibrary() {
    val runtimeJar = metroRuntimeJar()
    ModuleRootModificationUtil.addModuleLibrary(
      module,
      "metro-runtime",
      listOf(VfsUtil.getUrlForLibraryRoot(runtimeJar.toFile())),
      emptyList(),
    )
  }

  private fun metroRuntimeJar(): Path {
    return System.getProperty("metroRuntime.classpath")
      ?.split(File.pathSeparator)
      ?.map { Path.of(it) }
      ?.single {
        val fileName = it.fileName.toString()
        fileName.startsWith("runtime-jvm-") && fileName.endsWith(".jar")
      } ?: error("Unable to get a valid classpath from 'metroRuntime.classpath' property")
  }

  private fun setMetroEnabled(enabled: Boolean?) {
    if (enabled == null) {
      setMetroOptions()
    } else {
      setMetroOptions("enabled" to enabled.toString())
    }
  }

  private fun setMetroOptions(vararg options: Pair<String, String>) {
    KotlinCommonCompilerArgumentsHolder.getInstance(project).update {
      pluginOptions =
        options
          .map { (name, value) -> "plugin:$PLUGIN_ID:$name=$value" }
          .toTypedArray()
          .takeUnless { it.isEmpty() }
    }
  }
}

private fun KtFile.declarationsIncludingNested(): List<KtDeclaration> {
  val declarations = mutableListOf<KtDeclaration>()
  accept(
    object : KtTreeVisitorVoid() {
      override fun visitDeclaration(dcl: KtDeclaration) {
        declarations += dcl
        super.visitDeclaration(dcl)
      }
    }
  )
  return declarations
}

private fun List<KtDeclaration>.function(name: String): KtNamedFunction {
  return filterIsInstance<KtNamedFunction>().single { it.name == name }
}

private fun List<KtDeclaration>.property(name: String): KtProperty {
  return filterIsInstance<KtProperty>().single { it.name == name }
}

private fun List<KtDeclaration>.klass(name: String): KtClass {
  return filterIsInstance<KtClass>().single { it.name == name }
}

private fun List<KtDeclaration>.parameter(name: String): KtParameter {
  return PsiTreeUtil.findChildrenOfType(first().containingFile, KtParameter::class.java).single {
    it.name == name
  }
}
