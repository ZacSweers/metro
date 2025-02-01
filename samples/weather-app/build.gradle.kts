// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.plugin.serialization)
  id("dev.zacsweers.metro")
}

kotlin {
  jvm()
  /*
   TODO non-jvm targets fail with "IrValueParameterSymbolImpl is already bound" exceptions
    e: java.lang.IllegalStateException: IrValueParameterSymbolImpl is already bound. Signature: null.
    Owner: VALUE_PARAMETER INSTANCE_RECEIVER name:<this> type:<uninitialized parent>.$$MetroGraph
        at org.jetbrains.kotlin.ir.symbols.impl.IrSymbolBase.bind(IrSymbolImpl.kt:67)
        at org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl.<init>(IrValueParameterImpl.kt:48)
        at org.jetbrains.kotlin.ir.declarations.IrFactory.createValueParameter(IrFactory.kt:407)
        at org.jetbrains.kotlin.backend.common.serialization.IrDeclarationDeserializer.deserializeIrValueParameter
        (IrDeclarationDeserializer.kt:298)
        at org.jetbrains.kotlin.backend.common.serialization.IrDeclarationDeserializer.deserializeIrValueParameter
        $default(IrDeclarationDeserializer.kt:294)
  */
  // macosArm64()
  // js { browser() }
  // @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }
  sourceSets {
    commonMain {
      dependencies {
        implementation(libs.ktor.client)
        implementation(libs.ktor.client.contentNegotiation)
        implementation(libs.ktor.client.auth)
        implementation(libs.ktor.serialization.json)
        implementation(libs.okio)
        implementation(libs.picnic)
        implementation(libs.kotlinx.datetime)
      }
    }
    commonTest { dependencies { implementation(libs.kotlin.test) } }
    jvmMain {
      dependencies {
        implementation(libs.ktor.client.engine.okhttp)
        implementation(libs.clikt)
        implementation(libs.okhttp)
        // To silence this stupid log https://www.slf4j.org/codes.html#StaticLoggerBinder
        implementation(libs.slf4jNop)
      }
    }
  }
}

metro { debug.set(false) }
