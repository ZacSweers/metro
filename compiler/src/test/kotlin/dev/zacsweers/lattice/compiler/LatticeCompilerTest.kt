/*
 * Copyright (C) 2021 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.CompilationResult
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.addPreviousResultToClasspath
import dev.zacsweers.lattice.LatticeCommandLineProcessor
import dev.zacsweers.lattice.LatticeCommandLineProcessor.Companion.OPTION_DEBUG
import dev.zacsweers.lattice.LatticeCommandLineProcessor.Companion.OPTION_ENABLED
import dev.zacsweers.lattice.LatticeCommandLineProcessor.Companion.OPTION_GENERATE_ASSISTED_FACTORIES
import dev.zacsweers.lattice.LatticeCompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.junit.Rule
import org.junit.rules.TemporaryFolder

abstract class LatticeCompilerTest {

  @Rule @JvmField val temporaryFolder: TemporaryFolder = TemporaryFolder()

  protected fun prepareCompilation(
    vararg sourceFiles: SourceFile,
    debug: Boolean = false,
    generateAssistedFactories: Boolean = false,
    previousCompilationResult: JvmCompilationResult? = null,
  ): KotlinCompilation {
    return KotlinCompilation().apply {
      workingDir = temporaryFolder.root
      compilerPluginRegistrars = listOf(LatticeCompilerPluginRegistrar())
      val processor = LatticeCommandLineProcessor()
      commandLineProcessors = listOf(processor)
      pluginOptions =
        listOf(
          processor.option(OPTION_ENABLED, "true"),
          processor.option(OPTION_DEBUG, debug),
          processor.option(OPTION_GENERATE_ASSISTED_FACTORIES, generateAssistedFactories),
        )
      inheritClassPath = true
      sources = sourceFiles.asList()
      verbose = false
      jvmTarget = "11"
      // TODO need to support non-JVM invoke too
      kotlincArguments += "-Xjvm-default=all"

      if (previousCompilationResult != null) {
        addPreviousResultToClasspath(previousCompilationResult)
      }
    }
  }

  protected fun CommandLineProcessor.option(key: CliOption, value: Any?): PluginOption {
    return PluginOption(pluginId, key.optionName, value.toString())
  }

  protected fun compile(
    vararg sourceFiles: SourceFile,
    debug: Boolean = false,
    generateAssistedFactories: Boolean = false,
    expectedExitCode: KotlinCompilation.ExitCode = KotlinCompilation.ExitCode.OK,
    previousCompilationResult: JvmCompilationResult? = null,
    body: JvmCompilationResult.() -> Unit = {},
  ): JvmCompilationResult {
    return prepareCompilation(
        sourceFiles = sourceFiles,
        debug = debug,
        generateAssistedFactories = generateAssistedFactories,
        previousCompilationResult = previousCompilationResult,
      )
      .compile()
      .apply {
        if (exitCode != expectedExitCode) {
          throw AssertionError(
            "Compilation exited with $exitCode but expected ${expectedExitCode}:\n${messages}"
          )
        }
      }
      .apply(body)
  }

  protected fun CompilationResult.assertContains(message: String) {
    assertThat(messages).contains(message)
  }
}
