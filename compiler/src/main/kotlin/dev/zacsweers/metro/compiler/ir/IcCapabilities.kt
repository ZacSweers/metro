// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.platform.jvm.isJvm

internal data class IcCapabilities(
  val automaticDeclarationFinderTracking: Boolean,
  val annotationArgumentInvalidation: Boolean,
  val readableAnnotationMetadata: Boolean,
  val irGeneratedClasses: Boolean,
) {
  companion object {
    fun create(
      options: MetroOptions,
      compatContext: CompatContext,
      pluginContext: IrPluginContext,
    ): IcCapabilities {
      if (!options.omitRedundantMirrors) return None

      val platform = pluginContext.platform
      val readableAnnotationMetadata =
        platform.usesKlib() ||
          (platform.isJvm() &&
            pluginContext.languageVersionSettings.supportsFeature(
              LanguageFeature.AnnotationsInMetadata
            ))
      val supportsIrGeneratedClasses =
        platform.isJvm() && options.generateClassesInIr && compatContext.supportsIrGeneratedClasses

      return IcCapabilities(
        automaticDeclarationFinderTracking =
          compatContext.supportsAutomaticDeclarationFinderTracking,
        annotationArgumentInvalidation = compatContext.supportsAnnotationArgumentInvalidation,
        readableAnnotationMetadata = readableAnnotationMetadata,
        irGeneratedClasses = supportsIrGeneratedClasses,
      )
    }

    private val None =
      IcCapabilities(
        automaticDeclarationFinderTracking = false,
        annotationArgumentInvalidation = false,
        readableAnnotationMetadata = false,
        irGeneratedClasses = false,
      )
  }
}
