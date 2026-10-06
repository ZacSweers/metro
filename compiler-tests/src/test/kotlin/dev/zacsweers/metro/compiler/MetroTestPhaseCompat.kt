// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.TestInfrastructureInternals
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.TestPhaseDirectives
import org.jetbrains.kotlin.test.directives.model.RegisteredDirectives
import org.jetbrains.kotlin.test.directives.model.RegisteredDirectivesImpl
import org.jetbrains.kotlin.test.directives.model.SimpleDirective
import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer
import org.jetbrains.kotlin.test.directives.model.StringDirective
import org.jetbrains.kotlin.test.directives.model.ValueDirective
import org.jetbrains.kotlin.test.services.DefaultsProvider
import org.jetbrains.kotlin.test.services.ModuleStructureTransformer
import org.jetbrains.kotlin.test.services.TestModuleStructure
import org.jetbrains.kotlin.test.services.TestPhase

/** Keeps legacy BACKEND directives usable after Kotlin renamed the phase to CODEGEN. */
@OptIn(TestInfrastructureInternals::class)
internal fun TestConfigurationBuilder.configureTestPhaseCompat() {
  if (backendTestPhase.name != "CODEGEN") {
    return
  }
  useDirectives(MetroTestPhaseDirectives)
  useModuleStructureTransformers(MetroTestPhaseTransformer())
}

private object MetroTestPhaseDirectives : SimpleDirectivesContainer() {
  val RUN_PIPELINE_TILL by
    enumDirective<TestPhase>(
      description = "The phase through which a Metro fixture should compile.",
      additionalParser = {
        if (it == "BACKEND") {
          backendTestPhase
        } else {
          null
        }
      },
    )
}

/** Transfers phase values to Kotlin's directive after parsing the legacy phase name. */
@OptIn(TestInfrastructureInternals::class)
private class MetroTestPhaseTransformer : ModuleStructureTransformer() {
  override fun transformModuleStructure(
    moduleStructure: TestModuleStructure,
    defaultsProvider: DefaultsProvider,
  ): TestModuleStructure {
    return object : TestModuleStructure() {
      override val modules =
        moduleStructure.modules.map {
          it.copy(directives = it.directives.withCompatibleTestPhase())
        }
      override val allDirectives = moduleStructure.allDirectives.withCompatibleTestPhase()
      override val originalTestDataFiles = moduleStructure.originalTestDataFiles
    }
  }
}

private fun RegisteredDirectives.withCompatibleTestPhase(): RegisteredDirectives {
  val phaseDirective = MetroTestPhaseDirectives.RUN_PIPELINE_TILL
  if (phaseDirective !in this) {
    return this
  }

  val simpleDirectives = filterIsInstance<SimpleDirective>()
  val stringDirectives = filterIsInstance<StringDirective>().associateWith { this[it] }
  val valueDirectives =
    filterIsInstance<ValueDirective<*>>().associateWith { this[it] }.toMutableMap()
  valueDirectives.remove(phaseDirective)
  valueDirectives[TestPhaseDirectives.RUN_PIPELINE_TILL] = this[phaseDirective]
  return RegisteredDirectivesImpl(simpleDirectives, stringDirectives, valueDirectives)
}
