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
    requiresAndroidXDeps: Boolean = false,
  ) {
    project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
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

        if (!isComposeTarget && !requiresAndroidXDeps) {
          @OptIn(ExperimentalWasmDsl::class)
          wasmWasi {
            binaries.executable()
            nodejs()
          }
        }

        /////// Native targets
        // Sourced from https://kotlinlang.org/docs/native-target-support.html
        if (isComposeTarget) {
          // Compose-supported native targets
          macosArm64()
          iosSimulatorArm64()
          iosArm64()
          iosX64()
          @Suppress("DEPRECATION") macosX64()
        } else {
          // Tier 1
          macosArm64()
          iosSimulatorArm64()
          iosArm64()

          // Tier 2
          linuxX64()
          linuxArm64()
          watchosSimulatorArm64()
          watchosArm32()
          watchosArm64()
          tvosSimulatorArm64()
          tvosArm64()

          // Tier 3
          mingwX64()
          iosX64()
          @Suppress("DEPRECATION") macosX64()
          @Suppress("DEPRECATION") tvosX64()
          @Suppress("DEPRECATION") watchosX64()
          if (!requiresAndroidXDeps) {
            androidNativeArm32()
            androidNativeArm64()
            androidNativeX86()
            androidNativeX64()
            watchosDeviceArm64()
          }
        }

        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        applyDefaultHierarchyTemplate {
          common {
            group("wasm") {
              withWasmJs()
              if (!isComposeTarget && !requiresAndroidXDeps) {
                withWasmWasi()
              }
            }
            group("web") {
              withJs()
              withWasmJs()
              if (!isComposeTarget && !requiresAndroidXDeps) {
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
            compilations.configureEach {
              compileTaskProvider.configure {
                compilerOptions {
                  freeCompilerArgs.add(
                    "-Xklib-duplicated-unique-name-strategy=allow-all-with-warning"
                  )
                }
              }
            }
          }
      }
    }
  }
}
