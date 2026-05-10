// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.configuration.setupJvmPipelineSteps

/**
 * Sets up the standard JVM pipeline used by Metro's diagnostic and dump tests.
 *
 * Per-source-set shim because the underlying framework helper was renamed in Kotlin 2.4.20
 * (KT-85292): `commonConfigurationForJvmTest` -> `setupJvmPipelineSteps`. Each generator source set
 * provides a version-appropriate body; the test sources just call this single name.
 *
 * Note: `setupJvmPipelineSteps` already invokes `configureFirParser` internally, unlike the legacy
 * `commonConfigurationForJvmTest`. So no separate parser-config call here.
 */
fun TestConfigurationBuilder.setupMetroJvmPipeline(parser: FirParser) {
  setupJvmPipelineSteps(parser)
}
