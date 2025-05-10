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

private val daggerInteropClasspath =
  System.getProperty("daggerInterop.classpath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'daggerInterop.classpath' property")

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
    if (MetroDirectives.ENABLE_DAGGER_KSP in module.directives) {
      for (file in daggerInteropClasspath) {
        configuration.addJvmClasspathRoot(file)
      }
    }
  }
}

class DaggerInteropClassPathProvider(testServices: TestServices) :
  RuntimeClasspathProvider(testServices) {
  override fun runtimeClassPaths(module: TestModule): List<File> {
    return when (MetroDirectives.ENABLE_DAGGER_KSP in module.directives) {
      true -> daggerInteropClasspath
      false -> emptyList()
    }
  }
}
