// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.fir.generators.AssistedFactoryFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.AssistedFactoryImplFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.ContributedInterfaceSupertypeGenerator
import dev.zacsweers.metro.compiler.fir.generators.ContributionsFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.DependencyGraphFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.GraphFactoryFirSupertypeGenerator
import dev.zacsweers.metro.compiler.fir.generators.InjectedClassFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.LoggingFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.fir.generators.LoggingFirSupertypeGenerationExtension
import dev.zacsweers.metro.compiler.fir.generators.ProvidesFactoryFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.ProvidesFactorySupertypeGenerator
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension

public class MetroFirExtensionRegistrar(
  private val classIds: ClassIds,
  private val options: MetroOptions,
) : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +MetroFirBuiltIns.getFactory(classIds, options)
    +::MetroFirCheckers
    +supertypeGenerator("Supertypes - graph factory", ::GraphFactoryFirSupertypeGenerator, false)
    +supertypeGenerator(
      "Supertypes - contributed interfaces",
      ContributedInterfaceSupertypeGenerator.Factory(classIds)::create,
      false,
    )
    +supertypeGenerator(
      "Supertypes - provider factories",
      ::ProvidesFactorySupertypeGenerator,
      false,
    )
    // TODO enable once we support metadata propagation
    //  +::FirProvidesStatusTransformer
    +declarationGenerator("FirGen - InjectedClass", ::InjectedClassFirGenerator, true)
    if (options.generateAssistedFactories) {
      +declarationGenerator("FirGen - AssistedFactory", ::AssistedFactoryFirGenerator, true)
    }
    +declarationGenerator("FirGen - AssistedFactoryImpl", ::AssistedFactoryImplFirGenerator, true)
    +declarationGenerator("FirGen - ProvidesFactory", ::ProvidesFactoryFirGenerator, true)
    +declarationGenerator("FirGen - ContributionsGenerator", ::ContributionsFirGenerator, true)
    +declarationGenerator("FirGen - DependencyGraph", ::DependencyGraphFirGenerator, true)
  }

  private fun loggerFor(type: MetroLogger.Type, tag: String): MetroLogger {
    return if (type in options.enabledLoggers) {
      MetroLogger(type, System.out::println, tag)
    } else {
      MetroLogger.NONE
    }
  }

  private fun declarationGenerator(
    tag: String,
    delegate: ((FirSession) -> FirDeclarationGenerationExtension),
    enableLogging: Boolean = false,
  ): FirDeclarationGenerationExtension.Factory {
    return FirDeclarationGenerationExtension.Factory { session ->
      val logger =
        if (enableLogging) {
          loggerFor(MetroLogger.Type.FirDeclarationGeneration, tag)
        } else {
          MetroLogger.NONE
        }
      if (logger == MetroLogger.NONE) {
        delegate(session)
      } else {
        LoggingFirDeclarationGenerationExtension(session, logger, delegate(session))
      }
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
      if (logger == MetroLogger.NONE) {
        delegate(session)
      } else {
        LoggingFirSupertypeGenerationExtension(session, logger, delegate(session))
      }
    }
  }
}
