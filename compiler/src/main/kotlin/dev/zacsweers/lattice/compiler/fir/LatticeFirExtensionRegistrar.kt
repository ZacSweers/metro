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
package dev.zacsweers.lattice.compiler.fir

import dev.zacsweers.lattice.compiler.LatticeClassIds
import dev.zacsweers.lattice.compiler.LatticeLogger
import dev.zacsweers.lattice.compiler.LatticeOptions
import dev.zacsweers.lattice.compiler.fir.generators.AssistedFactoryFirGenerator
import dev.zacsweers.lattice.compiler.fir.generators.AssistedFactoryImplFirGenerator
import dev.zacsweers.lattice.compiler.fir.generators.DependencyGraphFirGenerator
import dev.zacsweers.lattice.compiler.fir.generators.GraphFactoryFirSupertypeGenerator
import dev.zacsweers.lattice.compiler.fir.generators.InjectedClassFirGenerator
import dev.zacsweers.lattice.compiler.fir.generators.LoggingFirDeclarationGenerationExtension
import dev.zacsweers.lattice.compiler.fir.generators.LoggingFirSupertypeGenerationExtension
import dev.zacsweers.lattice.compiler.fir.generators.ProvidesFactoryFirGenerator
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension

internal class LatticeFirExtensionRegistrar(
  private val latticeClassIds: LatticeClassIds,
  private val options: LatticeOptions,
) : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +LatticeFirBuiltIns.getFactory(latticeClassIds, options)
    +::LatticeFirCheckers
    // TODO this seems to break supertype lookups in some phases
    if (options.makeExistingCompanionsImplementGraphFactories) {
      +supertypeGenerator("Supertypes - graph factory", ::GraphFactoryFirSupertypeGenerator, true)
    }
    // TODO enable once we support metadata propagation
    //  +::FirProvidesStatusTransformer
    +declarationGenerator("FirGen - InjectedClass", ::InjectedClassFirGenerator, false)
    if (options.generateAssistedFactories) {
      +declarationGenerator("FirGen - AssistedFactory", ::AssistedFactoryFirGenerator, false)
    }
    +declarationGenerator("FirGen - AssistedFactoryImpl", ::AssistedFactoryImplFirGenerator, false)
    +declarationGenerator("FirGen - ProvidesFactory", ::ProvidesFactoryFirGenerator, false)
    +declarationGenerator("FirGen - DependencyGraph", ::DependencyGraphFirGenerator, true)
  }

  private fun loggerFor(type: LatticeLogger.Type, tag: String): LatticeLogger {
    return if (type in options.enabledLoggers) {
      LatticeLogger(type, System.out::println, tag)
    } else {
      LatticeLogger.NONE
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
          loggerFor(LatticeLogger.Type.FirDeclarationGeneration, tag)
        } else {
          LatticeLogger.NONE
        }
      if (logger == LatticeLogger.NONE) {
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
          loggerFor(LatticeLogger.Type.FirSupertypeGeneration, tag)
        } else {
          LatticeLogger.NONE
        }
      if (logger == LatticeLogger.NONE) {
        delegate(session)
      } else {
        LoggingFirSupertypeGenerationExtension(session, logger, delegate(session))
      }
    }
  }
}
