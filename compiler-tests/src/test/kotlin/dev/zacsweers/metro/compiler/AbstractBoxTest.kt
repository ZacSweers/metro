// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaConstructor
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.configureIrHandlersStep
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.IGNORE_DEXING
import org.jetbrains.kotlin.test.directives.ConfigurationDirectives.WITH_STDLIB
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.FULL_JDK
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.JVM_TARGET
import org.jetbrains.kotlin.test.model.BackendInputHandler
import org.jetbrains.kotlin.test.runners.codegen.AbstractFirLightTreeBlackBoxCodegenTest
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

@Suppress("UNCHECKED_CAST")
private val NoIrCompilationErrorsHandler =
  listOf(
      // 2.3.0 name
      "NoIrCompilationErrorsHandler",
      // 2.2.20 name
      "NoFir2IrCompilationErrorsHandler",
    )
    .firstNotNullOf {
      try {
        Class.forName("org.jetbrains.kotlin.test.backend.handlers.$it")
      } catch (_: ClassNotFoundException) {
        null
      }
    }
    .kotlin as KClass<BackendInputHandler<IrBackendInput>>?
    ?: error("Could not find NoIrCompilationErrorsHandler for the current kotlin version")

open class AbstractBoxTest :
  AbstractFirLightTreeBlackBoxCodegenTest(), LeakPatchingTest by LeakPatchingTest() {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return ClasspathBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) {
    super.configure(builder)

    with(builder) {
      registerLeakPatcher()
      configurePlugin()

      useSourcePreprocessor(::KotlinTestImportPreprocessor)

      useMetaTestConfigurators(::MetroTestConfigurator)

      defaultDirectives {
        JVM_TARGET.with(JvmTarget.JVM_11)
        +FULL_JDK
        +WITH_STDLIB

        +IGNORE_DEXING // Avoids loading R8 from the classpath.
      }

      configureIrHandlersStep {
        useHandlers(
          // Errors in compiler plugin backend should fail test without running box function.
          { NoIrCompilationErrorsHandler.primaryConstructor!!.javaConstructor!!.newInstance(it) })
      }
    }
  }
}
