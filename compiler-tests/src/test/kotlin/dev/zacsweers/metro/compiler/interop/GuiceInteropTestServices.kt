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

private val guiceClasspath =
  System.getProperty("guice.classpath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'guice.classpath' property")

fun TestConfigurationBuilder.configureGuiceInterop() {
  useConfigurators(::GuiceInteropEnvironmentConfigurator)
  useCustomRuntimeClasspathProviders(::GuiceInteropClassPathProvider)
}

class GuiceInteropEnvironmentConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  override fun configureCompilerConfiguration(
    configuration: CompilerConfiguration,
    module: TestModule,
  ) {
    val addGuiceInterop = MetroDirectives.enableGuiceInterop(module.directives)
    val addGuiceRuntime = MetroDirectives.enableGuiceAnnotations(module.directives)

    if (addGuiceInterop) {
      for (file in guiceClasspath) {
        configuration.addJvmClasspathRoot(file)
      }
    }

    // Add jakarta interop when Guice runtime is enabled
    if (addGuiceRuntime) {
      for (file in jakartaInteropClasspath) {
        configuration.addJvmClasspathRoot(file)
      }
    }
  }
}

class GuiceInteropClassPathProvider(testServices: TestServices) :
  RuntimeClasspathProvider(testServices) {
  override fun runtimeClassPaths(module: TestModule): List<File> {
    val paths = mutableListOf<File>()

    if (MetroDirectives.enableGuiceAnnotations(module.directives)) {
      paths.addAll(guiceClasspath)
      paths.addAll(jakartaInteropClasspath)
    }

    return paths
  }
}
