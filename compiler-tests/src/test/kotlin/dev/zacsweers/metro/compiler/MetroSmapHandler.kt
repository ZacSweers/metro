// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.codegen.ClassFileFactory
import org.jetbrains.kotlin.test.backend.handlers.JvmBinaryArtifactHandler
import org.jetbrains.kotlin.test.model.BinaryArtifacts
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

/** Checks for empty SMAP ranges when the test opts into the workaround. */
class MetroSmapHandler(testServices: TestServices) : JvmBinaryArtifactHandler(testServices) {
  override fun processModule(module: TestModule, info: BinaryArtifacts.Jvm) {
    if (MetroDirectives.FIX_EMPTY_SMAP_RANGES !in module.directives) {
      return
    }
    // The artifact's class moved in Kotlin 2.4.20, but every version exposes this property.
    val classFileFactory =
      info.javaClass.getMethod("getClassFileFactory").invoke(info) as ClassFileFactory
    for (file in classFileFactory.asList()) {
      if (!file.relativePath.endsWith(".class")) {
        continue
      }
      val smap = file.asByteArray().readSmap() ?: continue
      val emptyRanges = smap.lineSectionEntries().filter { it.isEmptyRange() }
      assertions.assertTrue(emptyRanges.isEmpty()) {
        "${file.relativePath} has zero-sized SMAP line ranges $emptyRanges:\n$smap"
      }
    }
  }

  override fun processAfterAllModules(someAssertionWasFailed: Boolean) {}

  private fun ByteArray.readSmap(): String? {
    var smap: String? = null
    ClassReader(this)
      .accept(
        object : ClassVisitor(Opcodes.API_VERSION) {
          override fun visitSource(source: String?, debug: String?) {
            smap = debug
          }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES,
      )
    return smap
  }

  /** Returns the entries of every `*L` section. The next `*` header ends a section. */
  private fun String.lineSectionEntries(): List<String> {
    val entries = mutableListOf<String>()
    var inLineSection = false
    for (line in lines()) {
      if (line.startsWith("*")) {
        inLineSection = line == "*L"
      } else if (inLineSection) {
        entries += line
      }
    }
    return entries
  }

  /** A line entry is `InputStartLine[#LineFileID][,RepeatCount]:OutputStartLine[,Increment]`. */
  private fun String.isEmptyRange(): Boolean {
    val input = substringBefore(':')
    val repeatCount = input.substringAfter(',', missingDelimiterValue = "")
    return repeatCount == "0"
  }
}
