// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.ir.betterDumpKotlinLike
import org.jetbrains.kotlin.ir.util.FakeOverridesStrategy
import org.jetbrains.kotlin.ir.util.KotlinLikeDumpOptions
import org.jetbrains.kotlin.test.backend.handlers.AbstractIrHandler
import org.jetbrains.kotlin.test.backend.handlers.IrTextDumpHandler.Companion.computeDumpExtension
import org.jetbrains.kotlin.test.backend.handlers.IrTextDumpHandler.Companion.groupWithTestFiles
import org.jetbrains.kotlin.test.backend.handlers.assertFileDoesntExist
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.EXTERNAL_FILE
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.model.BackendKind
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.utils.MultiModuleInfoDumper
import org.jetbrains.kotlin.test.utils.withExtension

/**
 * Like [org.jetbrains.kotlin.test.backend.handlers.IrPrettyKotlinDumpHandler] but uses
 * [betterDumpKotlinLike] so that nested class names are fully qualified in the dump output.
 *
 * Triggered by [MetroDirectives.METRO_DUMP_KT_IR] instead of `DUMP_KT_IR` to avoid conflicts with
 * the built-in handler.
 */
class MetroIrPrettyKotlinDumpHandler(
  testServices: TestServices,
  artifactKind: BackendKind<IrBackendInput>,
  compatContext: CompatContext,
) : AbstractIrHandler(testServices, artifactKind), CompatContext by compatContext {
  companion object {
    const val DUMP_EXTENSION = "kt.txt"
  }

  private val dumper = MultiModuleInfoDumper("// MODULE: %s")

  override val directiveContainers: List<DirectivesContainer>
    get() = listOf(MetroDirectives, FirDiagnosticsDirectives)

  override fun processModule(module: TestModule, info: IrBackendInput) {
    if (MetroDirectives.METRO_DUMP_KT_IR !in module.directives) return

    val options =
      KotlinLikeDumpOptions(
        printFilePath = false,
        printFakeOverridesStrategy = FakeOverridesStrategy.NONE,
        stableOrder = true,
        printExpectDeclarations = module.languageVersionSettings.languageVersion.usesK2,
        inferElseBranches = true,
      )

    val irFiles = info.irModuleFragment.files
    val builder = dumper.builderForModule(module.name)
    val allModules = testServices.moduleStructure.modules
    val filteredIrFiles =
      irFiles
        .groupWithTestFiles(testServices, ordered = true)
        .filterNot { (moduleAndFile, _) ->
          moduleAndFile?.second?.let { EXTERNAL_FILE in it.directives || it.isAdditional } ?: false
        }
        .map { it.second }
    val printFileName = filteredIrFiles.size > 1 || allModules.size > 1
    val modifiedOptions = options.copy(printFileName = printFileName)
    for (irFile in filteredIrFiles) {
      builder.append(irFile.betterDumpKotlinLike(modifiedOptions))
    }
  }

  override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
    val moduleStructure = testServices.moduleStructure
    val extension = computeDumpExtension(testServices, DUMP_EXTENSION)
    val expectedFile = moduleStructure.originalTestDataFiles.first().withExtension(extension)

    if (dumper.isEmpty()) {
      assertions.assertFileDoesntExist(expectedFile, MetroDirectives.METRO_DUMP_KT_IR)
    } else {
      assertions.assertEqualsToFile(expectedFile, dumper.generateResultingDump())
    }
  }
}
