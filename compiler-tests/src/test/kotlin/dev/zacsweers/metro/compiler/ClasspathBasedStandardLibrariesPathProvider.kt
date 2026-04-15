// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ClasspathBasedStandardLibrariesPathProvider : MetroKotlinStandardLibrariesPathProvider() {
  private val fileCache = ConcurrentHashMap<String, File>()

  private fun getFile(propName: String): File {
    return fileCache.computeIfAbsent(propName) {
      val path = System.getProperty(propName) ?: error("System property '$propName' not set")
      File(path)
    }
  }

  override fun runtimeJarForTests(): File = getFile("kotlin.minimal.stdlib.path")

  override fun runtimeJarForTestsWithJdk8(): File = getFile("kotlin.full.stdlib.path")

  override fun minimalRuntimeJarForTests(): File = getFile("kotlin.minimal.stdlib.path")

  override fun reflectJarForTests(): File = getFile("kotlin.reflect.jar.path")

  override fun kotlinTestJarForTests(): File = getFile("kotlin.test.jar.path")

  override fun scriptRuntimeJarForTests(): File = getFile("kotlin.script.runtime.path")

  override fun jvmAnnotationsForTests(): File = getFile("kotlin.annotations.path")

  override fun getAnnotationsJar(): File = getFile("kotlin.annotations.path")

  override fun fullJsStdlib(): File = getFile("kotlin.js.stdlib.path")

  override fun defaultJsStdlib(): File = getFile("kotlin.js.stdlib.path")

  override fun kotlinTestJsKLib(): File = getFile("kotlin.js.test.path")

  override fun scriptingPluginFilesForTests(): Collection<File> {
    TODO("KT-67573")
  }

  override fun commonStdlibForTests(): File = getFile("kotlin.common.stdlib.path")

  override fun webStdlibForTests(): File = getFile("kotlin.web.stdlib.path")

  // kotlin-stdlib-<WasmTarget>.klib
  override fun fullWasmStdlib(target: String): File {
    return getFile("kotlin.wasm.stdlib.$target.path")
  }

  // kotlin-test-<WasmTarget>.klib
  override fun kotlinTestWasmKLib(target: String): File {
    return getFile("kotlin.wasm.test.$target.path")
  }
}
