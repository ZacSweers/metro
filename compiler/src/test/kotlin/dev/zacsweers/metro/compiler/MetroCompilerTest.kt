// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.CompilationResult
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import com.tschuchort.compiletesting.addPreviousResultToClasspath
import kotlin.io.path.absolutePathString
import okio.Buffer
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.junit.Rule
import org.junit.rules.TemporaryFolder

abstract class MetroCompilerTest {

  @Rule @JvmField val temporaryFolder: TemporaryFolder = TemporaryFolder()

  // TODO every time we update this a ton of tests fail because their line numbers change
  //  would be nice to make this more flexible
  val defaultImports
    get() =
      listOf(
        "${Symbols.StringNames.METRO_RUNTIME_PACKAGE}.*",
        // For Callable access
        "java.util.concurrent.*",
      ) + extraImports

  protected open val extraImports: List<String>
    get() = emptyList()

  protected open val metroOptions: MetroOptions
    get() = MetroOptions()

  protected fun prepareCompilation(
    vararg sourceFiles: SourceFile,
    debug: Boolean = MetroOption.DEBUG.raw.defaultValue.expectAs(),
    generateAssistedFactories: Boolean =
      MetroOption.GENERATE_ASSISTED_FACTORIES.raw.defaultValue.expectAs(),
    options: MetroOptions =
      metroOptions.copy(debug = debug, generateAssistedFactories = generateAssistedFactories),
    previousCompilationResult: JvmCompilationResult? = null,
  ): KotlinCompilation {
    return KotlinCompilation().apply {
      workingDir = temporaryFolder.root
      compilerPluginRegistrars = listOf(MetroCompilerPluginRegistrar())
      val processor = MetroCommandLineProcessor()
      commandLineProcessors = listOf(processor)
      pluginOptions = options.toPluginOptions(processor)
      inheritClassPath = true
      sources = sourceFiles.asList()
      verbose = false
      jvmTarget = JVM_TARGET
      // TODO this is needed until/unless we implement JVM reflection support for DefaultImpls
      //  invocations
      kotlincArguments += "-Xjvm-default=all"

      // TODO test enabling IC?
      //  kotlincArguments += "-Xenable-incremental-compilation"

      if (previousCompilationResult != null) {
        addPreviousResultToClasspath(previousCompilationResult)
      }
    }
  }

  private fun MetroOptions.toPluginOptions(processor: CommandLineProcessor): List<PluginOption> {
    return sequence {
        for (entry in MetroOption.entries) {
          val option =
            when (entry) {
              MetroOption.DEBUG -> processor.option(entry.raw.cliOption, debug)
              MetroOption.ENABLED -> processor.option(entry.raw.cliOption, enabled)
              MetroOption.REPORTS_DESTINATION ->
                processor.option(
                  entry.raw.cliOption,
                  reportsDestination?.absolutePathString().orEmpty(),
                )
              MetroOption.GENERATE_ASSISTED_FACTORIES ->
                processor.option(entry.raw.cliOption, generateAssistedFactories)
              MetroOption.ENABLE_TOP_LEVEL_FUNCTION_INJECTION ->
                processor.option(entry.raw.cliOption, enableTopLevelFunctionInjection)
              MetroOption.PUBLIC_PROVIDER_SEVERITY ->
                processor.option(entry.raw.cliOption, publicProviderSeverity)
              MetroOption.LOGGING -> {
                if (enabledLoggers.isEmpty()) continue
                processor.option(entry.raw.cliOption, enabledLoggers.joinToString("|") { it.name })
              }
              MetroOption.ENABLE_DAGGER_RUNTIME_INTEROP ->
                processor.option(entry.raw.cliOption, enableDaggerRuntimeInterop)
              MetroOption.CUSTOM_PROVIDER -> {
                if (customProviderTypes.isEmpty()) continue
                processor.option(entry.raw.cliOption, customProviderTypes.joinToString(":"))
              }
              MetroOption.CUSTOM_LAZY -> {
                if (customLazyTypes.isEmpty()) continue
                processor.option(entry.raw.cliOption, customLazyTypes.joinToString(":"))
              }
              MetroOption.CUSTOM_ASSISTED -> {
                if (customAssistedAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customAssistedAnnotations.joinToString(":"))
              }
              MetroOption.CUSTOM_ASSISTED_FACTORY -> {
                if (customAssistedFactoryAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customAssistedFactoryAnnotations.joinToString(":"),
                )
              }
              MetroOption.CUSTOM_ASSISTED_INJECT -> {
                if (customAssistedInjectAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customAssistedInjectAnnotations.joinToString(":"),
                )
              }
              MetroOption.CUSTOM_BINDS -> {
                if (customBindsAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customBindsAnnotations.joinToString(":"))
              }
              MetroOption.CUSTOM_CONTRIBUTES_TO -> {
                if (customContributesToAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customContributesToAnnotations.joinToString(":"),
                )
              }
              MetroOption.CUSTOM_CONTRIBUTES_BINDING -> {
                if (customContributesBindingAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customContributesBindingAnnotations.joinToString(":"),
                )
              }
              MetroOption.CUSTOM_ELEMENTS_INTO_SET -> {
                if (customElementsIntoSetAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customElementsIntoSetAnnotations.joinToString(":"),
                )
              }
              MetroOption.CUSTOM_GRAPH -> {
                if (customGraphAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customGraphAnnotations.joinToString(":"))
              }
              MetroOption.CUSTOM_GRAPH_FACTORY -> {
                if (customGraphFactoryAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customGraphFactoryAnnotations.joinToString(":"),
                )
              }
              MetroOption.CUSTOM_INJECT -> {
                if (customInjectAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customInjectAnnotations.joinToString(":"))
              }
              MetroOption.CUSTOM_INTO_MAP -> {
                if (customIntoMapAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customIntoMapAnnotations.joinToString(":"))
              }
              MetroOption.CUSTOM_INTO_SET -> {
                if (customIntoSetAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customIntoSetAnnotations.joinToString(":"))
              }
              MetroOption.CUSTOM_MAP_KEY -> {
                if (customMapKeyAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customMapKeyAnnotations.joinToString(":"))
              }
              MetroOption.CUSTOM_MULTIBINDS -> {
                if (customMultibindsAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customMultibindsAnnotations.joinToString(":"))
              }
              MetroOption.CUSTOM_PROVIDES -> {
                if (customProvidesAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customProvidesAnnotations.joinToString(":"))
              }
              MetroOption.CUSTOM_QUALIFIER -> {
                if (customQualifierAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customQualifierAnnotations.joinToString(":"))
              }
              MetroOption.CUSTOM_SCOPE -> {
                if (customScopeAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customScopeAnnotations.joinToString(":"))
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
   * Metro.
   */
  protected fun source(
    @Language("kotlin") source: String,
    fileNameWithoutExtension: String? = null,
    packageName: String = "test",
    vararg extraImports: String,
  ): SourceFile {
    val fileName =
      fileNameWithoutExtension
        ?: CLASS_NAME_REGEX.find(source)?.groups?.get("name")?.value
        ?: FUNCTION_NAME_REGEX.find(source)?.groups?.get("name")?.value?.capitalizeUS()
        ?: "source"
    return kotlin(
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
    metroEnabled: Boolean = true,
    debug: Boolean = MetroOption.DEBUG.raw.defaultValue.expectAs(),
    generateAssistedFactories: Boolean =
      MetroOption.GENERATE_ASSISTED_FACTORIES.raw.defaultValue.expectAs(),
    options: MetroOptions =
      metroOptions.copy(
        enabled = metroEnabled,
        debug = debug,
        generateAssistedFactories = generateAssistedFactories,
      ),
    expectedExitCode: KotlinCompilation.ExitCode = KotlinCompilation.ExitCode.OK,
    compilationBlock: KotlinCompilation.() -> Unit = {},
    previousCompilationResult: JvmCompilationResult? = null,
    body: JvmCompilationResult.() -> Unit = {},
  ): JvmCompilationResult {
    val cleaningOutput = Buffer()
    val compilation =
      prepareCompilation(
          sourceFiles = sourceFiles,
          debug = debug,
          options = options,
          previousCompilationResult = previousCompilationResult,
        )
        .apply(compilationBlock)
        .apply { this.messageOutputStream = cleaningOutput.outputStream() }

    val result = compilation.compile()

    // Print cleaned output
    while (!cleaningOutput.exhausted()) {
      println(cleaningOutput.readUtf8Line()?.cleanOutputLine())
    }

    // Print generated files if debug is enabled
    if (debug) {
      compilation.workingDir
        .walkTopDown()
        .filter { file -> file.isFile && (file.extension.let { it == "kt" || it == "java" }) }
        .forEach { file ->
          println("Generated source file: ${file.name}")
          println(file.readText())
          println()
        }
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
    val CLASS_NAME_REGEX = Regex("(class|object|interface) (?<name>[a-zA-Z0-9_]+)")
    val FUNCTION_NAME_REGEX = Regex("fun( <[a-zA-Z0-9_]+>)? (?<name>[a-zA-Z0-9_]+)")

    val COMPOSE_ANNOTATIONS =
      kotlin(
        "Composable.kt",
        """
    package androidx.compose.runtime

    @Target(
      AnnotationTarget.FUNCTION,
      AnnotationTarget.TYPE,
      AnnotationTarget.TYPE_PARAMETER,
      AnnotationTarget.PROPERTY_GETTER
    )
    annotation class Composable

    @MustBeDocumented
    @Target(
        AnnotationTarget.CLASS,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY
    )
    @Retention(AnnotationRetention.BINARY)
    @StableMarker
    annotation class Stable

    @MustBeDocumented
    @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.BINARY)
    annotation class StableMarker
    """,
      )
  }
}
