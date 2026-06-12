// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.js.test.converters.Fir2IrCliWebFacade
import org.jetbrains.kotlin.js.test.converters.FirCliWebFacade
import org.jetbrains.kotlin.js.test.converters.FirKlibSerializerCliWebFacade
import org.jetbrains.kotlin.js.test.ir.commonConfigurationForJsTest
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.FrontendKinds

fun TestConfigurationBuilder.setupMetroJsPipeline(@Suppress("UNUSED_PARAMETER") parser: FirParser) {
  commonConfigurationForJsTest(
    targetFrontend = FrontendKinds.FIR,
    frontendFacade = ::FirCliWebFacade,
    frontendToIrConverter = ::Fir2IrCliWebFacade,
    serializerFacade = ::FirKlibSerializerCliWebFacade,
  )
}
