// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.interop

import dev.zacsweers.metro.compiler.MetroDirectives
import java.io.File
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices

/** Reads one fixture classpath supplied by the Gradle test task. */
private fun classpath(property: String): List<File> {
  val value =
    checkNotNull(System.getProperty(property)) { "Missing classpath property '$property'" }
  return value.split(File.pathSeparator).map(::File)
}

private val daggerRuntimeClasspath =
  classpath("daggerRuntime.classpath") +
    classpath("javaxInterop.classpath") +
    classpath("jakartaInterop.classpath")

private val daggerInteropClasspath = daggerRuntimeClasspath + classpath("daggerInterop.classpath")

/** [MetroDirectives.WITH_HILT] needs Dagger's annotations. Interop also needs Metro's bridge. */
private fun TestModule.daggerClasspath(): List<File> {
  return when {
    MetroDirectives.enableDaggerInterop(directives) -> daggerInteropClasspath
    MetroDirectives.WITH_HILT in directives -> daggerRuntimeClasspath
    else -> emptyList()
  }
}

fun TestConfigurationBuilder.configureDaggerInterop() {
  useConfigurators(::DaggerInteropEnvironmentConfigurator)
  useCustomRuntimeClasspathProviders(::DaggerInteropClassPathProvider)
}

class DaggerInteropEnvironmentConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  override fun configureCompilerConfiguration(
    configuration: CompilerConfiguration,
    module: TestModule,
  ) {
    for (file in module.daggerClasspath()) {
      configuration.addJvmClasspathRoot(file)
    }
  }
}

class DaggerInteropClassPathProvider(testServices: TestServices) :
  RuntimeClasspathProvider(testServices) {
  override fun runtimeClassPaths(module: TestModule): List<File> = module.daggerClasspath()
}
