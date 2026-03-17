// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import javax.inject.Inject
import kotlin.text.set
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JsModuleKind.MODULE_UMD
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

abstract class MetroProjectExtension
@Inject
constructor(private val project: Project, objects: ObjectFactory) {
  abstract val jvmTarget: Property<String>
  val languageVersion: Property<KotlinVersion> =
    objects.property(KotlinVersion::class.java).convention(KotlinVersion.DEFAULT)
  val apiVersion: Property<KotlinVersion> =
    objects.property(KotlinVersion::class.java).convention(KotlinVersion.DEFAULT)
  val progressiveMode: Property<Boolean> =
    objects
      .property(Boolean::class.java)
      .convention(languageVersion.map { it < KotlinVersion.DEFAULT })

  /*
   * Here's the main hierarchy of variants. Any `expect` functions in one level of the tree are
   * `actual` functions in a (potentially indirect) child node.
   *
   * ```
   *   common
   *   |-- jvm
   *   |-- js
   *   '-- native
   *       |- unix
   *       |   |-- apple
   *       |   |   |-- iosArm64
   *       |   |   |-- iosX64
   *       |   |   |-- tvosArm64
   *       |   |   |-- watchosArm32
   *       |   |   |-- watchosArm64
   *       |   |   '-- watchosX86
   *       |   '-- linux
   *       |       '-- linuxX64
   *       '-- mingw
   *           '-- mingwX64
   * ```
   *
   * Every child of `unix` also includes a source set that depends on the pointer size:
   *
   *  * `sizet32` for watchOS, including watchOS 64-bit architectures
   *  * `sizet64` for everything else
   */
  fun configureCommonKmpTargets(
    jsModuleName: String,
    includeAndroid: Boolean = false, // TODO
    isComposeTarget: Boolean = false,
  ) {
    project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
      // Sourced from https://kotlinlang.org/docs/native-target-support.html
      with(project.kotlinExtension as KotlinMultiplatformExtension) {
        jvm()
        js(IR) {
          outputModuleName.set("$jsModuleName-js")
          compilations.configureEach {
            compileTaskProvider.configure {
              compilerOptions {
                moduleKind.set(MODULE_UMD)
                sourceMap.set(true)
              }
            }
          }
          nodejs { testTask { useMocha { timeout = "30s" } } }
          browser()
          binaries.executable()
        }

        @OptIn(ExperimentalWasmDsl::class)
        wasmJs {
          outputModuleName.set("$jsModuleName-wasmjs")
          binaries.executable()
          browser {}
        }

        if (!isComposeTarget) {
          @OptIn(ExperimentalWasmDsl::class)
          wasmWasi {
            binaries.executable()
            nodejs()
          }
        }

        /////// Native targets
        if (isComposeTarget) {
          // Compose-supported native targets
          iosArm64()
          iosSimulatorArm64()
          iosX64()
          macosArm64()
          macosX64()
        } else {
          // Tier 1
          iosArm64()
          iosSimulatorArm64()
          macosArm64()

          // Tier 2
          linuxArm64()
          linuxX64()
          tvosArm64()
          tvosSimulatorArm64()
          watchosArm32()
          watchosArm64()
          watchosSimulatorArm64()

          // Tier 3
          androidNativeArm32()
          androidNativeArm64()
          androidNativeX64()
          androidNativeX86()
          iosX64()
          macosX64()
          mingwX64()
          tvosX64()
          watchosDeviceArm64()
          watchosX64()
        }

        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        applyDefaultHierarchyTemplate {
          common {
            group("wasm") {
              withWasmJs()
              if (!isComposeTarget) {
                withWasmWasi()
              }
            }
            group("web") {
              withJs()
              withWasmJs()
              if (!isComposeTarget) {
                withWasmWasi()
              }
            }
            group("nonWeb") {
              withJvm()
              withNative()
            }
          }
        }

        targets
          .matching {
            it.platformType == KotlinPlatformType.js || it.platformType == KotlinPlatformType.wasm
          }
          .configureEach {
            val target = this
            compilations.configureEach {
              compileTaskProvider.configure {
                compilerOptions {
                  freeCompilerArgs.add(
                    "-Xklib-duplicated-unique-name-strategy=allow-all-with-warning"
                  )
                  if (target.platformType == KotlinPlatformType.js) {
                    freeCompilerArgs.add(
                      // These are all read at compile-time
                      "-Xwarning-level=RUNTIME_ANNOTATION_NOT_SUPPORTED:disabled"
                    )
                  }
                }
              }
            }
          }
      }
    }
  }
}
