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
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  id("dev.zacsweers.lattice")
}

kotlin {
  jvm()
  macosArm64()
  js { browser() }
  @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }
  sourceSets {
    commonMain { dependencies { implementation(project(":runtime")) } }
    commonTest { dependencies { implementation(libs.kotlin.test) } }
  }
}

lattice { debug.set(false) }

configurations.configureEach {
  resolutionStrategy.dependencySubstitution {
    substitute(module("dev.zacsweers.lattice:runtime")).using(project(":runtime"))
    substitute(module("dev.zacsweers.lattice:runtime-jvm")).using(project(":runtime"))
    substitute(module("dev.zacsweers.lattice:compiler")).using(project(":compiler"))
  }
}
