// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.generators.AssistedFactoryFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.BindingMirrorClassFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.ContributedInterfaceSupertypeGenerator
import dev.zacsweers.metro.compiler.fir.generators.ContributionHintFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.ContributionsFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.DependencyGraphFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.GraphFactoryFirSupertypeGenerator
import dev.zacsweers.metro.compiler.fir.generators.InjectedClassFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.LoggingFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.fir.generators.LoggingFirStatusTransformerExtension
import dev.zacsweers.metro.compiler.fir.generators.LoggingFirSupertypeGenerationExtension
import dev.zacsweers.metro.compiler.fir.generators.ProvidesFactoryFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.ProvidesFactorySupertypeGenerator
import dev.zacsweers.metro.compiler.fir.generators.kotlinOnly
import kotlin.io.path.appendText
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension

public class MetroFirExtensionRegistrar(
  private val classIds: ClassIds,
  private val options: MetroOptions,
  private val compatContext: CompatContext,
) : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +MetroFirBuiltIns.getFactory(classIds, options)
    +::MetroFirCheckers
    +supertypeGenerator("Supertypes - graph factory", ::GraphFactoryFirSupertypeGenerator, false)
    +supertypeGenerator(
      "Supertypes - contributed interfaces",
      ::ContributedInterfaceSupertypeGenerator,
      true,
    )
    +supertypeGenerator(
      "Supertypes - provider factories",
      ::ProvidesFactorySupertypeGenerator,
      false,
    )
    if (options.transformProvidersToPrivate) {
      +statusTransformer("Status transformations - private", ::FirProvidesStatusTransformer, false)
    }
    +statusTransformer(
      "Status transformations - overrides",
      ::FirAccessorOverrideStatusTransformer,
      false,
    )
    +declarationGenerator("FirGen - InjectedClass", ::InjectedClassFirGenerator, true)
    if (options.generateAssistedFactories) {
      +declarationGenerator("FirGen - AssistedFactory", ::AssistedFactoryFirGenerator, true)
    }
    +declarationGenerator("FirGen - ProvidesFactory", ::ProvidesFactoryFirGenerator, true)
    +declarationGenerator("FirGen - BindingMirrorClass", ::BindingMirrorClassFirGenerator, true)
    +declarationGenerator("FirGen - ContributionsGenerator", ::ContributionsFirGenerator, true)
    if (options.generateContributionHints && options.generateContributionHintsInFir) {
      +declarationGenerator("FirGen - ContributionHints", ::ContributionHintFirGenerator, true)
    }
    +declarationGenerator("FirGen - DependencyGraph", ::DependencyGraphFirGenerator, true)

    registerDiagnosticContainers(MetroDiagnostics)
  }

  private fun loggerFor(type: MetroLogger.Type, tag: String): MetroLogger {
    return if (type in options.enabledLoggers) {
      val reportsDir = options.reportsDestination
      val output: (String) -> Unit =
        if (reportsDir != null) {
          val outputFile =
            reportsDir.resolve("fir-${type.name.lowercase()}-$tag.txt").apply {
              deleteIfExists()
              createParentDirectories()
              createFile()
            }
          val lambda: (String) -> Unit = { text: String ->
            if (options.debug) {
              println(text)
            }
            outputFile.appendText("\n$text")
          }
          lambda
        } else if (options.debug) {
          System.out::println
        } else {
          return MetroLogger.NONE
        }
      MetroLogger(type, output, tag)
    } else {
      MetroLogger.NONE
    }
  }

  private fun declarationGenerator(
    tag: String,
    delegate: ((FirSession, CompatContext) -> FirDeclarationGenerationExtension),
    enableLogging: Boolean = false,
  ): FirDeclarationGenerationExtension.Factory {
    return FirDeclarationGenerationExtension.Factory { session ->
      val logger =
        if (enableLogging) {
          loggerFor(MetroLogger.Type.FirDeclarationGeneration, tag)
        } else {
          MetroLogger.NONE
        }
      val extension =
        if (logger == MetroLogger.NONE) {
          delegate(session, compatContext)
        } else {
          LoggingFirDeclarationGenerationExtension(
            session,
            logger,
            delegate(session, compatContext),
          )
        }
      extension.kotlinOnly()
    }
  }

  private fun supertypeGenerator(
    tag: String,
    delegate: ((FirSession) -> FirSupertypeGenerationExtension),
    enableLogging: Boolean = false,
  ): FirSupertypeGenerationExtension.Factory {
    return FirSupertypeGenerationExtension.Factory { session ->
      val logger =
        if (enableLogging) {
          loggerFor(MetroLogger.Type.FirSupertypeGeneration, tag)
        } else {
          MetroLogger.NONE
        }
      val extension =
        if (logger == MetroLogger.NONE) {
          delegate(session)
        } else {
          LoggingFirSupertypeGenerationExtension(session, logger, delegate(session))
        }
      extension.kotlinOnly()
    }
  }

  private fun statusTransformer(
    tag: String,
    delegate: ((FirSession) -> FirStatusTransformerExtension),
    enableLogging: Boolean = false,
  ): FirStatusTransformerExtension.Factory {
    return FirStatusTransformerExtension.Factory { session ->
      val logger =
        if (enableLogging) {
          loggerFor(MetroLogger.Type.FirStatusTransformation, tag)
        } else {
          MetroLogger.NONE
        }
      val extension =
        if (logger == MetroLogger.NONE) {
          delegate(session)
        } else {
          LoggingFirStatusTransformerExtension(session, logger, delegate(session))
        }
      extension.kotlinOnly()
    }
  }
}
