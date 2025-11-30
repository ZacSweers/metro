// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.io.File
import java.io.File.pathSeparator
import java.io.File.separator

object ClasspathBasedStandardLibrariesPathProvider : MetroKotlinStandardLibrariesPathProvider() {
  private val SEP = "\\$separator"

  private val GRADLE_DEPENDENCY =
    (".*?" +
        SEP +
        "(?<name>[^$SEP]*)" +
        SEP +
        "(?<version>[^$SEP]*)" +
        SEP +
        "[^$SEP]*" +
        SEP +
        "\\1-\\2\\.jar")
      .toRegex()

  private val jars =
    System.getProperty("java.class.path")
      .split("\\$pathSeparator".toRegex())
      .dropLastWhile(String::isEmpty)
      .map(::File)
      .associateBy {
        GRADLE_DEPENDENCY.matchEntire(it.path)?.let { it.groups["name"]!!.value } ?: it.name
      }

  private fun getFile(name: String): File {
    return jars[name]
      ?: error("Jar $name not found in classpath:\n${jars.entries.joinToString("\n")}")
  }

  override fun runtimeJarForTests(): File = getFile("kotlin-stdlib")

  override fun runtimeJarForTestsWithJdk8(): File = getFile("kotlin-stdlib-jdk8")

  override fun minimalRuntimeJarForTests(): File = getFile("kotlin-stdlib")

  override fun reflectJarForTests(): File = getFile("kotlin-reflect")

  override fun kotlinTestJarForTests(): File = getFile("kotlin-test")

  override fun scriptRuntimeJarForTests(): File = getFile("kotlin-script-runtime")

  override fun jvmAnnotationsForTests(): File = getFile("kotlin-annotations-jvm")

  override fun getAnnotationsJar(): File = getFile("kotlin-annotations-jvm")

  override fun fullJsStdlib(): File = getFile("kotlin-stdlib-js")

  override fun defaultJsStdlib(): File = getFile("kotlin-stdlib-js")

  override fun kotlinTestJsKLib(): File = getFile("kotlin-test-js")

  override fun scriptingPluginFilesForTests(): Collection<File> {
    TODO("KT-67573")
  }

  override fun commonStdlibForTests(): File = getFile("kotlin-common-stdlib")
}
