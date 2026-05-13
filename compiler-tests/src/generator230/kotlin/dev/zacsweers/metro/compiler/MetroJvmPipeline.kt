// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.backend.ir.BackendCliJvmFacade
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.configuration.commonConfigurationForJvmTest
import org.jetbrains.kotlin.test.frontend.fir.Fir2IrCliJvmFacade
import org.jetbrains.kotlin.test.frontend.fir.FirCliJvmFacade
import org.jetbrains.kotlin.test.model.FrontendKinds

/**
 * Sets up the standard JVM pipeline used by Metro's diagnostic and dump tests.
 *
 * Per-source-set shim because the underlying framework helper was renamed in Kotlin 2.4.20
 * (KT-85292): `commonConfigurationForJvmTest` -> `setupJvmPipelineSteps`. Each generator source set
 * provides a version-appropriate body; the test sources just call this single name.
 *
 * Does NOT call `configureFirParser` -- callers are responsible for that. Reason: the 2.4.20+
 * `setupJvmPipelineSteps` includes parser config internally, so any extra call here would
 * double-register `FIR_PARSER`. v2.3.21's `singleOrZeroValue` doesn't `.distinct()` and would error
 * on the duplicate (master added `.distinct()`, hence the divergence).
 */
fun TestConfigurationBuilder.setupMetroJvmPipeline(
  @Suppress("UNUSED_PARAMETER") parser: FirParser
) {
  commonConfigurationForJvmTest(
    targetFrontend = FrontendKinds.FIR,
    frontendFacade = ::FirCliJvmFacade,
    frontendToBackendConverter = ::Fir2IrCliJvmFacade,
    backendFacade = ::BackendCliJvmFacade,
  )
}
