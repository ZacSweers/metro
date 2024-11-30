/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.atomicfu)
  id("dev.zacsweers.lattice")
}

kotlin {
  jvm()
  // TODO non-jvm targets fail with
  //  e: Compilation failed: class org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl cannot be
  //  cast to class org.jetbrains.kotlin.ir.expressions.IrBody
  //  (org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl and
  //  org.jetbrains.kotlin.ir.expressions.IrBody are in unnamed module of loader
  //  java.net.URLClassLoader @4965ed6f)
  //  Notes:
  //  - When implementing overrides for accessors, their expression bodies don't appear to get
  //    serialized correctly.
  //  - They generate something like `irExpressionBody(irInvoke(<provider>.invoke())`
  //  - However, for some reason the underling IrCall is what's serialized and the surrounding
  //    IrBody is missing
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

lattice { debug.set(false) }

tasks.withType<Test>().configureEach {
  maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
}

configurations.configureEach {
  resolutionStrategy.dependencySubstitution {
    substitute(module("dev.zacsweers.lattice:runtime")).using(project(":runtime"))
    substitute(module("dev.zacsweers.lattice:runtime-jvm")).using(project(":runtime"))
    substitute(module("dev.zacsweers.lattice:compiler")).using(project(":compiler"))
  }
}
