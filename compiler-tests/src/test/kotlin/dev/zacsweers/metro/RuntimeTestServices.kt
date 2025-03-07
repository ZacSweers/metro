// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import java.io.File
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices

private val metroRuntimeClasspath =
  System.getProperty("metroRuntime.classpath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'metroRuntime.classpath' property")

class RuntimeEnvironmentConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  override fun configureCompilerConfiguration(
    configuration: CompilerConfiguration,
    module: TestModule,
  ) {
    for (file in metroRuntimeClasspath) {
      configuration.addJvmClasspathRoot(file)
    }
  }
}

class MetroRuntimeClassPathProvider(testServices: TestServices) :
  RuntimeClasspathProvider(testServices) {
  override fun runtimeClassPaths(module: TestModule): List<File> {
    return metroRuntimeClasspath
  }
}
