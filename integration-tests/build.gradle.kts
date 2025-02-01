// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.multiplatform)
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
    commonMain { dependencies { implementation(project(":runtime")) } }
    commonTest {
      dependencies {
        implementation(libs.okio)
        implementation(libs.okio.fakefilesystem)
        implementation(libs.kotlin.test)
      }
    }
  }
}

metro { debug.set(false) }

tasks.withType<Test>().configureEach {
  maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
}

configurations.configureEach {
  resolutionStrategy.dependencySubstitution {
    substitute(module("dev.zacsweers.metro:runtime")).using(project(":runtime"))
    substitute(module("dev.zacsweers.metro:runtime-jvm")).using(project(":runtime"))
    substitute(module("dev.zacsweers.metro:compiler")).using(project(":compiler"))
  }
}
