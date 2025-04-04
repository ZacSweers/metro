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

private val anvilRuntimeClasspath =
  System.getProperty("anvilRuntime.classpath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'anvilRuntime.classpath' property")

fun TestConfigurationBuilder.configureAnvilAnnotations() {
  useConfigurators(::AnvilRuntimeEnvironmentConfigurator)
  useCustomRuntimeClasspathProviders(::AnvilRuntimeClassPathProvider)
}

class AnvilRuntimeEnvironmentConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  override fun configureCompilerConfiguration(
    configuration: CompilerConfiguration,
    module: TestModule,
  ) {
    if (MetroDirectives.WITH_ANVIL in module.directives) {
      for (file in anvilRuntimeClasspath) {
        configuration.addJvmClasspathRoot(file)
      }
    }
  }
}

class AnvilRuntimeClassPathProvider(testServices: TestServices) :
  RuntimeClasspathProvider(testServices) {
  override fun runtimeClassPaths(module: TestModule): List<File> {
    return when (MetroDirectives.WITH_ANVIL in module.directives) {
      true -> anvilRuntimeClasspath
      false -> emptyList()
    }
  }
}
