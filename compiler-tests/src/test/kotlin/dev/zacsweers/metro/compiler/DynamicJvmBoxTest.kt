// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/** Runs generated Kotlin source through the standard JVM box test runner. */
abstract class DynamicJvmBoxTest : AbstractBoxTest() {

  /** Writes a temporary test fixture, runs it, and removes the file afterward. */
  protected fun runDynamicBoxTest(testName: String, source: StringBuilder.() -> Unit) {
    val testData = createTempFile(prefix = testName, suffix = ".kt")

    try {
      testData.writeText(buildString(source))
      runTest(testData.toString())
    } finally {
      testData.deleteIfExists()
    }
  }

  /** Appends an injected binding chain with optional leaf and root dependencies. */
  protected fun StringBuilder.appendInjectedBindingChain(
    bindingCount: Int,
    namePrefix: String = "Node",
    leafDependency: String? = null,
    rootDependencies: List<String> = emptyList(),
    rootBody: String? = null,
  ) {
    for (index in 0 until bindingCount) {
      append("@Inject class ")
      append(namePrefix)
      append(index.toString().padStart(4, '0'))

      val hasNextBinding = index + 1 < bindingCount
      val hasOrdinaryDependency = hasNextBinding || leafDependency != null
      val hasRootDependencies = index == 0 && rootDependencies.isNotEmpty()
      if (hasOrdinaryDependency || hasRootDependencies) {
        append('(')
        if (hasOrdinaryDependency) {
          append("dependency: ")
          if (hasNextBinding) {
            append(namePrefix)
            append((index + 1).toString().padStart(4, '0'))
          } else {
            append(leafDependency)
          }
        }

        if (hasRootDependencies) {
          var needsComma = hasOrdinaryDependency
          for (rootDependency in rootDependencies) {
            if (needsComma) append(", ")
            append(rootDependency)
            needsComma = true
          }
        }
        append(')')
      }

      if (index == 0 && rootBody != null) {
        append(' ')
        append(rootBody)
      }
      appendLine()
    }
  }
}
