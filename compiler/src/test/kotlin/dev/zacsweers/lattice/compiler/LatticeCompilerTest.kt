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
import okio.Buffer
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.junit.Rule
import org.junit.rules.TemporaryFolder

abstract class LatticeCompilerTest {

  @Rule @JvmField val temporaryFolder: TemporaryFolder = TemporaryFolder()

  val defaultImports =
    listOf(
      "${LatticeSymbols.StringNames.latticeRuntimePackage}.*",
      // For Callable access
      "java.util.concurrent.*",
    )

  protected fun prepareCompilation(
    vararg sourceFiles: SourceFile,
    debug: Boolean = LatticeOption.DEBUG.raw.defaultValue.expectAs(),
    generateAssistedFactories: Boolean =
      LatticeOption.GENERATE_ASSISTED_FACTORIES.raw.defaultValue.expectAs(),
    options: LatticeOptions =
      LatticeOptions(debug = debug, generateAssistedFactories = generateAssistedFactories),
    previousCompilationResult: JvmCompilationResult? = null,
  ): KotlinCompilation {
    return KotlinCompilation().apply {
      workingDir = temporaryFolder.root
      compilerPluginRegistrars = listOf(LatticeCompilerPluginRegistrar())
      val processor = LatticeCommandLineProcessor()
      commandLineProcessors = listOf(processor)
      pluginOptions = options.toPluginOptions(processor)
      inheritClassPath = true
      sources = sourceFiles.asList()
      verbose = false
      jvmTarget = JVM_TARGET
      // TODO this is needed until/unless we implement JVM reflection support for DefaultImpls
      //  invocations
      kotlincArguments += "-Xjvm-default=all"

      if (previousCompilationResult != null) {
        addPreviousResultToClasspath(previousCompilationResult)
      }
    }
  }

  private fun LatticeOptions.toPluginOptions(processor: CommandLineProcessor): List<PluginOption> {
    return sequence {
        for (entry in LatticeOption.entries) {
          val option =
            when (entry) {
              LatticeOption.DEBUG -> processor.option(entry.raw.cliOption, debug)
              LatticeOption.ENABLED -> processor.option(entry.raw.cliOption, enabled)
              LatticeOption.GENERATE_ASSISTED_FACTORIES ->
                processor.option(entry.raw.cliOption, generateAssistedFactories)
              LatticeOption.LOGGING -> {
                if (enabledLoggers.isEmpty()) continue
                processor.option(entry.raw.cliOption, enabledLoggers.joinToString("|") { it.name })
              }
            }
          yield(option)
        }
      }
      .toList()
  }

  protected fun CommandLineProcessor.option(key: CliOption, value: Any?): PluginOption {
    return PluginOption(pluginId, key.optionName, value.toString())
  }

  /**
   * Returns a [SourceFile] representation of this [source]. This includes common imports from
   * Lattice.
   */
  protected fun source(
    @Language("kotlin") source: String,
    fileNameWithoutExtension: String? = null,
    packageName: String = "test",
    vararg extraImports: String,
  ): SourceFile {
    val fileName =
      fileNameWithoutExtension ?: CLASS_NAME_REGEX.find(source)?.groups?.get(2)?.value ?: "source"
    return SourceFile.kotlin(
      "${fileName}.kt",
      buildString {
        // Package statement
        appendLine("package $packageName")

        // Imports
        for (import in defaultImports + extraImports) {
          appendLine("import $import")
        }

        appendLine()
        appendLine()
        appendLine(source)
      },
    )
  }

  protected fun compile(
    vararg sourceFiles: SourceFile,
    debug: Boolean = LatticeOption.DEBUG.raw.defaultValue.expectAs(),
    generateAssistedFactories: Boolean =
      LatticeOption.GENERATE_ASSISTED_FACTORIES.raw.defaultValue.expectAs(),
    options: LatticeOptions =
      LatticeOptions(debug = debug, generateAssistedFactories = generateAssistedFactories),
    expectedExitCode: KotlinCompilation.ExitCode = KotlinCompilation.ExitCode.OK,
    previousCompilationResult: JvmCompilationResult? = null,
    body: JvmCompilationResult.() -> Unit = {},
  ): JvmCompilationResult {
    val cleaningOutput = Buffer()
    val result =
      prepareCompilation(
          sourceFiles = sourceFiles,
          debug = debug,
          options = options,
          previousCompilationResult = previousCompilationResult,
        )
        .apply { this.messageOutputStream = cleaningOutput.outputStream() }
        .compile()

    // Print cleaned output
    while (!cleaningOutput.exhausted()) {
      println(cleaningOutput.readUtf8Line()?.cleanOutputLine(includeSeverity = true))
    }

    return result
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

  companion object {
    val CLASS_NAME_REGEX = Regex("(class|object|interface) ([a-zA-Z0-9_]+)")
  }
}
