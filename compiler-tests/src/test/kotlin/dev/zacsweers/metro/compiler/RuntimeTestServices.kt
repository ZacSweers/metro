// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.io.File
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.defaultsProvider

private val metroRuntimeClasspath =
  System.getProperty("metroRuntime.classpath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'metroRuntime.classpath' property")

private val metroRuntimeCoroutinesClasspath =
  System.getProperty("metroRuntimeCoroutines.classpath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'metroRuntimeCoroutines.classpath' property")

private val metroRuntimeKlibClasspath =
  System.getProperty("metroRuntime.klibClasspath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'metroRuntime.klibClasspath' property")

private val metroRuntimeCoroutinesKlibClasspath =
  System.getProperty("metroRuntimeCoroutines.klibClasspath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'metroRuntimeCoroutines.klibClasspath' property")

private val coroutinesClasspath =
  System.getProperty("coroutines.classpath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'coroutines.classpath' property")

private val runtimeTracingClasspath =
  System.getProperty("runtimeTracing.classpath")?.split(File.pathSeparator)?.map(::File)
    ?: error("Unable to get a valid classpath from 'runtimeTracing.classpath' property")

internal fun TestServices.isJsBackend(): Boolean {
  val targetBackend = defaultsProvider.targetBackend
  return targetBackend == TargetBackend.JS_IR || targetBackend == TargetBackend.JS_IR_ES6
}

class MetroRuntimeEnvironmentConfigurator(testServices: TestServices) :
  EnvironmentConfigurator(testServices) {
  override fun configureCompilerConfiguration(
    configuration: CompilerConfiguration,
    module: TestModule,
  ) {
    if (testServices.isJsBackend()) return

    for (file in metroRuntimeClasspath) {
      configuration.addJvmClasspathRoot(file)
    }
    val withMetroRuntimeCoroutines =
      MetroDirectives.includeMetroRuntimeCoroutines(module.directives)
    if (withMetroRuntimeCoroutines) {
      for (file in metroRuntimeCoroutinesClasspath) {
        configuration.addJvmClasspathRoot(file)
      }
    }
    if (withMetroRuntimeCoroutines) {
      for (file in coroutinesClasspath) {
        configuration.addJvmClasspathRoot(file)
      }
    }
    if (MetroDirectives.ENABLE_RUNTIME_TRACING in module.directives) {
      for (file in runtimeTracingClasspath) {
        configuration.addJvmClasspathRoot(file)
      }
    }
  }
}

class MetroRuntimeClassPathProvider(testServices: TestServices) :
  RuntimeClasspathProvider(testServices) {
  override fun runtimeClassPaths(module: TestModule): List<File> {
    val withMetroRuntimeCoroutines =
      MetroDirectives.includeMetroRuntimeCoroutines(module.directives)
    if (testServices.isJsBackend()) {
      return if (withMetroRuntimeCoroutines) {
        metroRuntimeKlibClasspath + metroRuntimeCoroutinesKlibClasspath
      } else {
        metroRuntimeKlibClasspath
      }
    }
    return buildList {
      addAll(metroRuntimeClasspath)
      if (withMetroRuntimeCoroutines) {
        addAll(metroRuntimeCoroutinesClasspath)
      }
      if (withMetroRuntimeCoroutines) {
        addAll(coroutinesClasspath)
      }
      if (MetroDirectives.ENABLE_RUNTIME_TRACING in module.directives) {
        addAll(runtimeTracingClasspath)
      }
    }
  }
}
