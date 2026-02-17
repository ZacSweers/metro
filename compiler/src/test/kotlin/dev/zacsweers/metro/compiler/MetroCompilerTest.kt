// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.CompilationResult
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.SourceFile.Companion.java
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import com.tschuchort.compiletesting.addPreviousResultToClasspath
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import okio.Buffer
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.junit.Rule
import org.junit.rules.TemporaryFolder

abstract class MetroCompilerTest {

  @Rule @JvmField val temporaryFolder: TemporaryFolder = TemporaryFolder()
  @Rule @JvmField val testInfo: TestInfoRule = TestInfoRule()

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

  protected val debugOutputDir: Path
    get() =
      Paths.get(System.getProperty("metro.buildDir"))
        .resolve("metroDebug")
        .resolve(testInfo.currentClassName.substringAfterLast('.'))
        .resolve(testInfo.currentMethodName.replace(" ", "_"))

  private var compilationCount = 0

  protected fun prepareCompilation(
    vararg sourceFiles: SourceFile,
    debug: Boolean = MetroOption.DEBUG.raw.defaultValue.expectAs(),
    generateAssistedFactories: Boolean =
      MetroOption.GENERATE_ASSISTED_FACTORIES.raw.defaultValue.expectAs(),
    options: MetroOptions =
      metroOptions.copy(debug = debug, generateAssistedFactories = generateAssistedFactories),
    previousCompilationResult: JvmCompilationResult? = null,
    compilationName: String = "compilation${compilationCount++}",
  ): KotlinCompilation {
    val finalOptions = options.copy(debug = debug || options.debug)
    return KotlinCompilation().apply {
      workingDir = temporaryFolder.newFolder(compilationName)
      compilerPluginRegistrars = listOf(MetroCompilerPluginRegistrar())
      val processor = MetroCommandLineProcessor()
      commandLineProcessors = listOf(processor)
      pluginOptions = finalOptions.toPluginOptions(processor)
      inheritClassPath = true
      sources = sourceFiles.asList()
      verbose = false
      jvmTarget = JVM_TARGET
      kotlincArguments +=
        listOf("-jvm-default=no-compatibility", "-Xverify-ir=error", "-Xverify-ir-visibility")

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
              DEBUG -> processor.option(entry.raw.cliOption, debug)
              ENABLED -> processor.option(entry.raw.cliOption, enabled)
              REPORTS_DESTINATION ->
                processor.option(
                  entry.raw.cliOption,
                  reportsDir.value?.absolutePathString().orEmpty(),
                )
              TRACE_DESTINATION ->
                processor.option(
                  entry.raw.cliOption,
                  traceDir.value?.absolutePathString().orEmpty(),
                )
              GENERATE_ASSISTED_FACTORIES ->
                processor.option(entry.raw.cliOption, generateAssistedFactories)
              ENABLE_TOP_LEVEL_FUNCTION_INJECTION ->
                processor.option(entry.raw.cliOption, enableTopLevelFunctionInjection)
              GENERATE_CONTRIBUTION_HINTS ->
                processor.option(entry.raw.cliOption, generateContributionHints)
              GENERATE_CONTRIBUTION_HINTS_IN_FIR ->
                processor.option(
                  entry.raw.cliOption,
                  this@toPluginOptions.generateContributionHintsInFir,
                )
              TRANSFORM_PROVIDERS_TO_PRIVATE ->
                processor.option(entry.raw.cliOption, transformProvidersToPrivate)
              SHRINK_UNUSED_BINDINGS -> processor.option(entry.raw.cliOption, shrinkUnusedBindings)
              CHUNK_FIELD_INITS -> processor.option(entry.raw.cliOption, chunkFieldInits)
              STATEMENTS_PER_INIT_FUN -> processor.option(entry.raw.cliOption, statementsPerInitFun)
              ENABLE_GRAPH_SHARDING -> processor.option(entry.raw.cliOption, enableGraphSharding)
              KEYS_PER_GRAPH_SHARD -> processor.option(entry.raw.cliOption, keysPerGraphShard)
              PUBLIC_PROVIDER_SEVERITY ->
                processor.option(entry.raw.cliOption, publicProviderSeverity)
              NON_PUBLIC_CONTRIBUTION_SEVERITY ->
                processor.option(entry.raw.cliOption, nonPublicContributionSeverity)
              USE_ASSISTED_PARAM_NAMES_AS_IDENTIFIERS ->
                processor.option(entry.raw.cliOption, useAssistedParamNamesAsIdentifiers)
              ASSISTED_IDENTIFIER_SEVERITY ->
                processor.option(entry.raw.cliOption, assistedIdentifierSeverity)
              WARN_ON_INJECT_ANNOTATION_PLACEMENT ->
                processor.option(entry.raw.cliOption, warnOnInjectAnnotationPlacement)
              INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY ->
                processor.option(entry.raw.cliOption, interopAnnotationsNamedArgSeverity)
              UNUSED_GRAPH_INPUTS_SEVERITY ->
                processor.option(entry.raw.cliOption, unusedGraphInputsSeverity)
              ENABLE_SWITCHING_PROVIDERS ->
                processor.option(entry.raw.cliOption, enableSwitchingProviders)
              LOGGING -> {
                if (enabledLoggers.isEmpty()) continue
                processor.option(entry.raw.cliOption, enabledLoggers.joinToString("|") { it.name })
              }
              ENABLE_DAGGER_RUNTIME_INTEROP ->
                processor.option(entry.raw.cliOption, enableDaggerRuntimeInterop)
              MAX_IR_ERRORS_COUNT -> processor.option(entry.raw.cliOption, maxIrErrorsCount)
              CUSTOM_PROVIDER -> {
                if (customProviderTypes.isEmpty()) continue
                processor.option(entry.raw.cliOption, customProviderTypes.joinToString(":"))
              }
              CUSTOM_LAZY -> {
                if (customLazyTypes.isEmpty()) continue
                processor.option(entry.raw.cliOption, customLazyTypes.joinToString(":"))
              }
              CUSTOM_ASSISTED -> {
                if (customAssistedAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customAssistedAnnotations.joinToString(":"))
              }
              CUSTOM_ASSISTED_FACTORY -> {
                if (customAssistedFactoryAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customAssistedFactoryAnnotations.joinToString(":"),
                )
              }
              CUSTOM_ASSISTED_INJECT -> {
                if (customAssistedInjectAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customAssistedInjectAnnotations.joinToString(":"),
                )
              }
              CUSTOM_BINDS -> {
                if (customBindsAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customBindsAnnotations.joinToString(":"))
              }
              CUSTOM_CONTRIBUTES_TO -> {
                if (customContributesToAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customContributesToAnnotations.joinToString(":"),
                )
              }
              CUSTOM_CONTRIBUTES_BINDING -> {
                if (customContributesBindingAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customContributesBindingAnnotations.joinToString(":"),
                )
              }
              CUSTOM_ELEMENTS_INTO_SET -> {
                if (customElementsIntoSetAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customElementsIntoSetAnnotations.joinToString(":"),
                )
              }
              CUSTOM_DEPENDENCY_GRAPH -> {
                if (customGraphAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customGraphAnnotations.joinToString(":"))
              }
              CUSTOM_DEPENDENCY_GRAPH_FACTORY -> {
                if (customGraphFactoryAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customGraphFactoryAnnotations.joinToString(":"),
                )
              }
              CUSTOM_INJECT -> {
                if (customInjectAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customInjectAnnotations.joinToString(":"))
              }
              CUSTOM_INTO_MAP -> {
                if (customIntoMapAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customIntoMapAnnotations.joinToString(":"))
              }
              CUSTOM_INTO_SET -> {
                if (customIntoSetAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customIntoSetAnnotations.joinToString(":"))
              }
              CUSTOM_MAP_KEY -> {
                if (customMapKeyAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customMapKeyAnnotations.joinToString(":"))
              }
              CUSTOM_MULTIBINDS -> {
                if (customMultibindsAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customMultibindsAnnotations.joinToString(":"))
              }
              CUSTOM_PROVIDES -> {
                if (customProvidesAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customProvidesAnnotations.joinToString(":"))
              }
              CUSTOM_QUALIFIER -> {
                if (customQualifierAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customQualifierAnnotations.joinToString(":"))
              }
              CUSTOM_SCOPE -> {
                if (customScopeAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customScopeAnnotations.joinToString(":"))
              }
              CUSTOM_BINDING_CONTAINER -> {
                if (customBindingContainerAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customBindingContainerAnnotations.joinToString(":"),
                )
              }
              CUSTOM_CONTRIBUTES_INTO_SET -> {
                if (customContributesIntoSetAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customContributesIntoSetAnnotations.joinToString(":"),
                )
              }
              CUSTOM_GRAPH_EXTENSION -> {
                if (customGraphExtensionAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customGraphExtensionAnnotations.joinToString(":"),
                )
              }
              CUSTOM_GRAPH_EXTENSION_FACTORY -> {
                if (customGraphExtensionFactoryAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customGraphExtensionFactoryAnnotations.joinToString(":"),
                )
              }
              CUSTOM_ORIGIN -> {
                if (customOriginAnnotations.isEmpty()) continue
                processor.option(entry.raw.cliOption, customOriginAnnotations.joinToString(":"))
              }
              CUSTOM_OPTIONAL_BINDING -> {
                if (customOptionalBindingAnnotations.isEmpty()) continue
                processor.option(
                  entry.raw.cliOption,
                  customOptionalBindingAnnotations.joinToString(":"),
                )
              }
              ENABLE_DAGGER_ANVIL_INTEROP -> {
                processor.option(entry.raw.cliOption, enableDaggerAnvilInterop)
              }
              ENABLE_FULL_BINDING_GRAPH_VALIDATION -> {
                processor.option(entry.raw.cliOption, enableFullBindingGraphValidation)
              }
              ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE -> {
                processor.option(entry.raw.cliOption, enableGraphImplClassAsReturnType)
              }
              OPTIONAL_BINDING_BEHAVIOR -> {
                processor.option(entry.raw.cliOption, optionalBindingBehavior)
              }
              CONTRIBUTES_AS_INJECT -> {
                processor.option(entry.raw.cliOption, contributesAsInject)
              }
              DEDUPLICATE_INJECTED_PARAMS -> {
                processor.option(entry.raw.cliOption, deduplicateInjectedParams)
              }
              ENABLE_KLIB_PARAMS_CHECK -> {
                processor.option(entry.raw.cliOption, enableKlibParamsCheck)
              }
              PATCH_KLIB_PARAMS -> {
                processor.option(entry.raw.cliOption, patchKlibParams)
              }
              INTEROP_INCLUDE_JAVAX_ANNOTATIONS -> {
                processor.option(entry.raw.cliOption, false)
              }
              INTEROP_INCLUDE_JAKARTA_ANNOTATIONS -> {
                processor.option(entry.raw.cliOption, false)
              }
              INTEROP_INCLUDE_DAGGER_ANNOTATIONS -> {
                processor.option(entry.raw.cliOption, false)
              }
              INTEROP_INCLUDE_KOTLIN_INJECT_ANNOTATIONS -> {
                processor.option(entry.raw.cliOption, false)
              }
              INTEROP_INCLUDE_ANVIL_ANNOTATIONS -> {
                processor.option(entry.raw.cliOption, false)
              }
              INTEROP_INCLUDE_KOTLIN_INJECT_ANVIL_ANNOTATIONS -> {
                processor.option(entry.raw.cliOption, false)
              }
              ENABLE_GUICE_RUNTIME_INTEROP -> {
                processor.option(entry.raw.cliOption, enableGuiceRuntimeInterop)
              }
              INTEROP_INCLUDE_GUICE_ANNOTATIONS -> {
                processor.option(entry.raw.cliOption, false)
              }
              FORCE_ENABLE_FIR_IN_IDE -> {
                processor.option(entry.raw.cliOption, forceEnableFirInIde)
              }
              PLUGIN_ORDER_SET -> {
                processor.option(entry.raw.cliOption, pluginOrderSet?.toString().orEmpty())
              }
              COMPILER_VERSION -> {
                processor.option(
                  entry.raw.cliOption,
                  this@toPluginOptions.compilerVersion.orEmpty(),
                )
              }
              COMPILER_VERSION_ALIASES -> {
                processor.option(
                  entry.raw.cliOption,
                  this@toPluginOptions.compilerVersionAliases
                    .map { (k, v) -> "$k=$v" }
                    .joinToString(":"),
                )
              }
              PARALLEL_METRO_THREADS -> {
                processor.option(entry.raw.cliOption, parallelMetroThreads)
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
        for (import in (defaultImports + extraImports)) {
          appendLine("import $import")
        }

        appendLine()
        appendLine()
        appendLine(source)
      },
    )
  }

  /**
   * Returns a [SourceFile] representation of this [source]. This includes common imports from
   * Metro.
   */
  protected fun sourceJava(
    @Language("java") source: String,
    fileNameWithoutExtension: String? = null,
    packageName: String = "test",
    vararg extraImports: String,
  ): SourceFile {
    val fileName =
      fileNameWithoutExtension
        ?: CLASS_NAME_REGEX.find(source)?.groups?.get("name")?.value
        ?: FUNCTION_NAME_REGEX.find(source)?.groups?.get("name")?.value?.capitalizeUS()
        ?: "source"
    return java(
      "${fileName}.java",
      buildString {
        // Package statement
        appendLine("package $packageName;")

        // Imports
        for (import in (defaultImports + extraImports)) {
          appendLine("import $import;")
        }

        appendLine()
        appendLine()
        appendLine(source)
      },
    )
  }

  @IgnorableReturnValue
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
    compilationName: String = "compilation${compilationCount++}",
    body: JvmCompilationResult.() -> Unit = {},
  ): JvmCompilationResult {
    val cleaningOutput = Buffer()
    val compilation =
      prepareCompilation(
          sourceFiles = sourceFiles,
          debug = debug,
          options = options,
          previousCompilationResult = previousCompilationResult,
          compilationName = compilationName,
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
        .filter { file -> file.extension.let { it == "kt" || it == "java" } }
        .filterNot {
          // Don't print test sources
          it.absolutePath.contains("sources")
        }
        .forEach { file ->
          println("Generated source file: ${file.name}")
          println(file.readText())
          println()
        }

      val targetDir = debugOutputDir.resolve(compilationName).toFile()
      compilation.workingDir.copyRecursively(targetDir, overwrite = true)
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
