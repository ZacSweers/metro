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
  }

  fun testDoesNotMarkUnsupportedDeclarationsAsImplicitlyUsed() {
    val declarations = kotlinFileDeclarations()

    assertFalse(declarations.function("unusedFunction").isMetroImplicitUsage())
    assertFalse(declarations.klass("ClassAnnotatedInject").isMetroImplicitUsage())
    assertFalse(declarations.property("memberInject").isMetroImplicitUsage())
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
    val file = configureMetroFile()

    val declarations = mutableListOf<KtDeclaration>()
    file.accept(
      object : KtTreeVisitorVoid() {
        override fun visitDeclaration(dcl: KtDeclaration) {
          declarations += dcl
          super.visitDeclaration(dcl)
        }
      }
    )
    return declarations
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

      @Inject class ClassAnnotatedInject(service: Service)

      class MemberInjectedService {
        @Inject lateinit var memberInject: Service
        @Inject fun functionInject(service: Service) = Unit
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
