// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import org.jetbrains.kotlin.psi.KtFile

/**
 * Compares live Analysis API graph validation with checked-in compiler graph reports. Regenerate
 * the reports with `./gradlew :compiler-tests:test -PupdateTestData=true` when a compiler behavior
 * intentionally changes.
 */
class MetroGraphValidationParityTest : BasePlatformTestCase() {
  private val compilerContracts = CompilerContractReader()

  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    module.addKotlinStdlibLibrary()
    project.service<MetroGraphValidationService>().clearResults()
  }

  fun testCoreResolutionMatchesCompiler() {
    assertParity(
      ParityCase(
        fixtureName = "CoreGraph",
        graphPath = listOf("parity.core.AppGraph"),
        metadataReport = "CoreGraph/graph-metadata/graph-parity-core-AppGraph.json.txt",
        populatedReport = "CoreGraph/keys-populated/parity/core/AppGraph/Impl.txt",
        validatedReport = "CoreGraph/keys-validated/parity/core/AppGraph/Impl.txt",
        deferredReport = "CoreGraph/keys-deferred/parity/core/AppGraph/Impl.txt",
      )
    )
  }

  fun testGraphCompositionMatchesCompiler() {
    assertParity(
      ParityCase(
        fixtureName = "CompositionGraph",
        graphPath = listOf("parity.composition.AppGraph"),
        metadataReport =
          "CompositionGraph/graph-metadata/graph-parity-composition-AppGraph.json.txt",
        populatedReport = "CompositionGraph/keys-populated/parity/composition/AppGraph/Impl.txt",
        validatedReport = "CompositionGraph/keys-validated/parity/composition/AppGraph/Impl.txt",
        deferredReport = "CompositionGraph/keys-deferred/parity/composition/AppGraph/Impl.txt",
      )
    )
  }

  fun testAggregationMatchesCompiler() {
    assertParity(
      ParityCase(
        fixtureName = "AggregationGraph",
        graphPath = listOf("parity.aggregation.AppGraph"),
        metadataReport =
          "AggregationGraph/graph-metadata/graph-parity-aggregation-AppGraph.json.txt",
        populatedReport = "AggregationGraph/keys-populated/parity/aggregation/AppGraph/Impl.txt",
        validatedReport = "AggregationGraph/keys-validated/parity/aggregation/AppGraph/Impl.txt",
        deferredReport = "AggregationGraph/keys-deferred/parity/aggregation/AppGraph/Impl.txt",
      )
    )
  }

  fun testGraphExtensionMatchesCompiler() {
    val fixture = "ExtensionGraph"
    assertParity(
      ParityCase(
        fixtureName = fixture,
        graphPath = listOf("parity.extension.AppGraph"),
        metadataReport = "$fixture/graph-metadata/graph-parity-extension-AppGraph.json.txt",
        populatedReport = "$fixture/keys-populated/parity/extension/AppGraph/Impl.txt",
        validatedReport = "$fixture/keys-validated/parity/extension/AppGraph/Impl.txt",
        deferredReport = "$fixture/keys-deferred/parity/extension/AppGraph/Impl.txt",
      )
    )
    assertParity(
      ParityCase(
        fixtureName = fixture,
        graphPath = listOf("parity.extension.ChildGraph", "parity.extension.AppGraph"),
        populatedReport =
          "$fixture/keys-populated/parity/extension/AppGraph/Impl/ChildGraphImpl.txt",
        validatedReport =
          "$fixture/keys-validated/parity/extension/AppGraph/Impl/ChildGraphImpl.txt",
        deferredReport = "$fixture/keys-deferred/parity/extension/AppGraph/Impl/ChildGraphImpl.txt",
      )
    )
  }

  fun testMultiParentGraphExtensionMatchesCompiler() {
    val fixture = "MultiParentExtension"
    for (parent in listOf("LeftGraph", "RightGraph")) {
      val reportPath = "parity/extension/multi/$parent/Impl"
      assertParity(
        ParityCase(
          fixtureName = fixture,
          graphPath = listOf("parity.extension.multi.$parent"),
          populatedReport = "$fixture/keys-populated/$reportPath.txt",
          validatedReport = "$fixture/keys-validated/$reportPath.txt",
          deferredReport = "$fixture/keys-deferred/$reportPath.txt",
        )
      )
      assertParity(
        ParityCase(
          fixtureName = fixture,
          graphPath = listOf("parity.extension.multi.ChildGraph", "parity.extension.multi.$parent"),
          populatedReport = "$fixture/keys-populated/$reportPath/ChildGraphImpl.txt",
          validatedReport = "$fixture/keys-validated/$reportPath/ChildGraphImpl.txt",
          deferredReport = "$fixture/keys-deferred/$reportPath/ChildGraphImpl.txt",
        )
      )
    }
  }

  fun testValidationFailuresMatchCompiler() {
    val cases =
      listOf(
        failureCase("MissingBinding", "missing", hasPopulatedReport = true),
        failureCase("DuplicateBinding", "duplicate", hasPopulatedReport = false),
        failureCase("DependencyCycle", "cycle", hasPopulatedReport = true),
        failureCase("DuplicateMapKey", "mapkey", hasPopulatedReport = true),
        failureCase("EmptyMultibinding", "empty", hasPopulatedReport = true),
        failureCase("IncompatibleScope", "scope", hasPopulatedReport = true),
      )
    for (case in cases) assertParity(case)
  }

  fun testBinaryResolutionMatchesCompiler() {
    assertParity(
      ParityCase(
        fixtureName = "BinaryGraph",
        graphPath = listOf("parity.binary.BinaryGraph"),
        metadataReport = "BinaryGraph/graph-metadata/graph-parity-binary-BinaryGraph.json.txt",
        populatedReport = "BinaryGraph/keys-populated/parity/binary/BinaryGraph/Impl.txt",
        validatedReport = "BinaryGraph/keys-validated/parity/binary/BinaryGraph/Impl.txt",
        deferredReport = "BinaryGraph/keys-deferred/parity/binary/BinaryGraph/Impl.txt",
        sourceModule = "main",
        sourceFile = "BinaryGraph.kt",
        withLibrary = true,
      )
    )
  }

  private fun failureCase(
    fixtureName: String,
    packageName: String,
    hasPopulatedReport: Boolean,
  ): ParityCase {
    val report =
      if (hasPopulatedReport) {
        "$fixtureName/keys-populated/parity/failures/$packageName/AppGraph/Impl.txt"
      } else {
        null
      }
    return ParityCase(
      fixtureName = fixtureName,
      graphPath = listOf("parity.failures.$packageName.AppGraph"),
      populatedReport = report,
      diagnosticReport = "$fixtureName.ir.diag.txt",
    )
  }

  private fun assertParity(case: ParityCase) {
    val runComparison = {
      val source = compilerContracts.source(case)
      val file =
        myFixture.configureByText(case.sourceFile ?: "${case.fixtureName}.kt", source) as KtFile
      val index = project.service<MetroResolutionService>().index(file)
      val graphName = case.graphPath.first()
      val graph = index.graphs.single { it.classId?.asFqNameString() == graphName }
      val context =
        index.contextsFor(graph).single { context ->
          context.chain.mapNotNull { it.classId?.asFqNameString() } == case.graphPath
        }
      val result =
        project.service<MetroGraphValidationService>().validate(file, context).requireCompleted()
      val expected = compilerContracts.contract(case)
      val actual = ValidationContract.fromIdea(context, index, result)
      expected.assertMatches(actual, case.fixtureName)
    }
    if (case.withLibrary) {
      module.withMetroLibFixtureLibrary { runComparison() }
    } else {
      runComparison()
    }
  }
}
