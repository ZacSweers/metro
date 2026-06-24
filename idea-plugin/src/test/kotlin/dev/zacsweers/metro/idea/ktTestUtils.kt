// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

internal fun Module.addMetroRuntimeLibrary() {
  ModuleRootModificationUtil.addModuleLibrary(
    this,
    "metro-runtime",
    listOf(VfsUtil.getUrlForLibraryRoot(metroRuntimeJar().toFile())),
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

private const val LIB_FIXTURE_NAME = "metro-lib-fixture"

// The platform persists per-path jar index caches across test runs, which go stale when the
// fixture jar is rebuilt at the same path. Copy it to a unique temp path once per test JVM.
private val libFixtureJar: Path by lazy {
  val jar =
    System.getProperty("metroLibFixture.classpath")?.let(Path::of)
      ?: error("Unable to get a valid path from 'metroLibFixture.classpath' property")
  val copy = Files.createTempFile("metro-lib-fixture", ".jar")
  Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING)
  copy.toFile().deleteOnExit()
  copy
}

/**
 * Runs [body] with the compiled `libFixture` jar (Metro-annotated classes + contribution hints)
 * attached as a module library. Light fixtures reuse the module across tests, so the library is
 * removed afterwards to avoid leaking into other tests.
 */
internal fun Module.withMetroLibFixtureLibrary(
  withinProject: Boolean = false,
  body: () -> Unit,
) {
  val jar =
    if (withinProject) {
      // Simulates a binary produced under the project path. Project ownership alone must not make
      // internal hints visible without a formal friend/associated compilation relationship.
      val base = Path.of(checkNotNull(project.basePath) { "No project base path" })
      Files.createDirectories(base)
      val copy = Files.createTempFile(base, "metro-lib-fixture", ".jar")
      Files.copy(libFixtureJar, copy, StandardCopyOption.REPLACE_EXISTING)
      copy.toFile().deleteOnExit()
      copy
    } else {
      libFixtureJar
    }
  ModuleRootModificationUtil.addModuleLibrary(
    this,
    LIB_FIXTURE_NAME,
    listOf(VfsUtil.getUrlForLibraryRoot(jar.toFile())),
    emptyList(),
  )
  try {
    body()
  } finally {
    ModuleRootModificationUtil.updateModel(this) { model ->
      val table = model.moduleLibraryTable
      table.libraries.filter { it.name == LIB_FIXTURE_NAME }.forEach(table::removeLibrary)
    }
  }
}

internal fun Project.setMetroOptions(vararg options: Pair<String, String>) {
  KotlinCommonCompilerArgumentsHolder.getInstance(this).update {
    pluginOptions =
      options
        .map { (name, value) -> "plugin:$PLUGIN_ID:$name=$value" }
        .toTypedArray()
        .takeUnless { it.isEmpty() }
  }
}

internal fun KtFile.declarationsIncludingNested(): List<KtDeclaration> {
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

internal fun List<KtDeclaration>.function(name: String): KtNamedFunction {
  return filterIsInstance<KtNamedFunction>().single { it.name == name }
}

internal fun List<KtDeclaration>.property(name: String): KtProperty {
  return filterIsInstance<KtProperty>().single { it.name == name }
}

internal fun List<KtDeclaration>.klass(name: String): KtClass {
  return filterIsInstance<KtClass>().single { it.name == name }
}

internal fun List<KtDeclaration>.obj(name: String): KtObjectDeclaration {
  return filterIsInstance<KtObjectDeclaration>().single { it.name == name }
}

internal fun List<KtDeclaration>.parameter(name: String): KtParameter {
  return PsiTreeUtil.findChildrenOfType(first().containingFile, KtParameter::class.java).single {
    it.name == name
  }
}

internal fun CodeInsightTestFixture.addCircuitStubs() {
  addFileToProject(
    "circuit/Stubs.kt",
    """
    package com.slack.circuit.runtime

    interface CircuitUiState

    interface Navigator

    interface CircuitContext
    """
      .trimIndent(),
  )
  addFileToProject(
    "circuit/Screen.kt",
    """
    package com.slack.circuit.runtime.screen

    interface Screen
    """
      .trimIndent(),
  )
  addFileToProject(
    "circuit/Ui.kt",
    """
    package com.slack.circuit.runtime.ui

    import com.slack.circuit.runtime.CircuitUiState

    interface Ui<S : CircuitUiState> {
      interface Factory
    }
    """
      .trimIndent(),
  )
  addFileToProject(
    "circuit/Presenter.kt",
    """
    package com.slack.circuit.runtime.presenter

    import com.slack.circuit.runtime.CircuitUiState

    interface Presenter<S : CircuitUiState> {
      interface Factory
    }
    """
      .trimIndent(),
  )
  addFileToProject(
    "circuit/CircuitInject.kt",
    """
    package com.slack.circuit.codegen.annotations

    import kotlin.reflect.KClass

    annotation class CircuitInject(val screen: KClass<*>, val scope: KClass<*>)
    """
      .trimIndent(),
  )
  addFileToProject(
    "compose/Modifier.kt",
    """
    package androidx.compose.ui

    interface Modifier
    """
      .trimIndent(),
  )
}
