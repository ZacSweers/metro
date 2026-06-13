// Copyright (C) 2026 Zac Sweers
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

private val hiltCoreClasspath =
  System.getProperty("hiltCore.classpath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'hiltCore.classpath' property")

fun TestConfigurationBuilder.configureHiltAnnotations() {
  useConfigurators(::HiltCoreEnvironmentConfigurator)
  useCustomRuntimeClasspathProviders(::HiltCoreClassPathProvider)
}

class HiltCoreEnvironmentConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  override fun configureCompilerConfiguration(
    configuration: CompilerConfiguration,
    module: TestModule,
  ) {
    if (MetroDirectives.enableHilt(module.directives)) {
      for (file in hiltCoreClasspath) {
        configuration.addJvmClasspathRoot(file)
      }
    }
  }
}

class HiltCoreClassPathProvider(testServices: TestServices) :
  RuntimeClasspathProvider(testServices) {
  override fun runtimeClassPaths(module: TestModule): List<File> =
    if (MetroDirectives.enableHilt(module.directives)) hiltCoreClasspath else emptyList()
}
