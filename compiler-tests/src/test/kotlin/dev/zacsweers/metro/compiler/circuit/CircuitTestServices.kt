// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.MetroDirectives
import java.io.File
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices

private val circuitClasspath =
  System.getProperty("circuit.classpath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'circuit.classpath' property")

fun TestConfigurationBuilder.configureCircuit() {
  useConfigurators(::CircuitEnvironmentConfigurator)
  useCustomRuntimeClasspathProviders(::CircuitClassPathProvider)
}

class CircuitEnvironmentConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  override fun configureCompilerConfiguration(
    configuration: CompilerConfiguration,
    module: TestModule,
  ) {
    val addCircuit = MetroDirectives.ENABLE_CIRCUIT in module.directives

    if (addCircuit) {
      for (file in circuitClasspath) {
        configuration.addJvmClasspathRoot(file)
      }
    }
  }
}

class CircuitClassPathProvider(testServices: TestServices) :
  RuntimeClasspathProvider(testServices) {
  override fun runtimeClassPaths(module: TestModule): List<File> {
    val paths = mutableListOf<File>()

    if (MetroDirectives.ENABLE_CIRCUIT in module.directives) {
      paths.addAll(circuitClasspath)
    }

    return paths
  }
}
