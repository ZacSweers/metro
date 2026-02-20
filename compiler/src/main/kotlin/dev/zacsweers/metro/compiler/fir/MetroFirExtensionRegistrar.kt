// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.generators.AssistedFactoryFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.BindingMirrorClassFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.CompositeMetroFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.fir.generators.ContributedInterfaceSupertypeGenerator
import dev.zacsweers.metro.compiler.fir.generators.ContributionHintFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.ContributionsFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.DependencyGraphFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.GraphFactoryFirSupertypeGenerator
import dev.zacsweers.metro.compiler.fir.generators.InjectedClassFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.LoggingFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.fir.generators.LoggingFirSupertypeGenerationExtension
import dev.zacsweers.metro.compiler.fir.generators.ProvidesFactoryFirGenerator
import dev.zacsweers.metro.compiler.fir.generators.ProvidesFactorySupertypeGenerator
import dev.zacsweers.metro.compiler.fir.generators.kotlinOnly
import java.util.ServiceLoader
import kotlin.io.path.appendText
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension

public class MetroFirExtensionRegistrar(
  private val classIds: ClassIds,
  private val options: MetroOptions,
  private val isIde: Boolean,
  private val compatContext: CompatContext,
  private val loadExternalDeclarationExtensions:
    (FirSession, MetroOptions) -> List<MetroFirDeclarationGenerationExtension> =
    ::loadExternalDeclarationExtensions,
  private val loadExternalContributionExtensions:
    (FirSession, MetroOptions) -> List<MetroContributionExtension> =
    ::loadExternalContributionExtensions,
) : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +MetroFirBuiltIns.getFactory(classIds, options, compatContext)
    +::MetroFirCheckers
    +supertypeGenerator("Supertypes - graph factory", ::GraphFactoryFirSupertypeGenerator, false)
    +supertypeGenerator(
      "Supertypes - contributed interfaces",
      { session, compatContext ->
        ContributedInterfaceSupertypeGenerator(
          session,
          compatContext,
          loadExternalContributionExtensions,
        )
      },
      true,
    )

    // These are types
    if (!isIde) {
      +supertypeGenerator(
        "Supertypes - provider factories",
        ::ProvidesFactorySupertypeGenerator,
        false,
      )
      +{ session: FirSession -> FirAccessorOverrideStatusTransformer(session, compatContext) }
    }
    if (options.transformProvidersToPrivate) {
      +{ session: FirSession -> FirProvidesStatusTransformer(session, compatContext) }
    }

    // Register the composite declaration generator that includes external extensions
    +compositeDeclarationGenerator()

    registerDiagnosticContainers(MetroDiagnostics)
  }

  /**
   * Creates a composite declaration generator that combines:
   * 1. External extensions loaded via ServiceLoader (processed first)
   * 2. Metro's native declaration generators (processed after)
   *
   * This allows external extensions to generate code that Metro's native generators can consume.
   */
  private fun compositeDeclarationGenerator(): FirDeclarationGenerationExtension.Factory {
    return FirDeclarationGenerationExtension.Factory { session ->
      // Don't use isIde as isCli() is available here and a bit more precise
      val isCli = session.isCli()

      // Load external extensions via ServiceLoader
      val externalExtensions = loadExternalDeclarationExtensions(session, options)

      // Build list of native Metro generators
      val nativeExtensions = buildList {
        // Don't gate on isCli because this also handles top-level function gen
        add(
          wrapNativeGenerator("FirGen - InjectedClass", true, ::InjectedClassFirGenerator)(session)
        )

        // Don't gate on isCli because these are user-visible
        if (options.generateAssistedFactories) {
          add(
            wrapNativeGenerator("FirGen - AssistedFactory", true, ::AssistedFactoryFirGenerator)(
              session
            )
          )
        }

        // These need to run in the IDE for supertype merging inlays to be visible
        add(
          wrapNativeGenerator("FirGen - ContributionsGenerator", true, ::ContributionsFirGenerator)(
            session
          )
        )

        if (isCli) {
          add(
            wrapNativeGenerator("FirGen - ProvidesFactory", true, ::ProvidesFactoryFirGenerator)(
              session
            )
          )

          add(
            wrapNativeGenerator(
              "FirGen - BindingMirrorClass",
              true,
              ::BindingMirrorClassFirGenerator,
            )(session)
          )

          if (options.generateContributionHints && options.generateContributionHintsInFir) {
            add(
              wrapNativeGenerator("FirGen - ContributionHints", true) { session, compatContext ->
                ContributionHintFirGenerator(session, compatContext, externalExtensions)
              }(session)
            )
          }
        }

        add(
          wrapNativeGenerator("FirGen - DependencyGraph", true, ::DependencyGraphFirGenerator)(
            session
          )
        )
      }

      CompositeMetroFirDeclarationGenerationExtension(
          session = session,
          externalExtensions = externalExtensions,
          nativeExtensions = nativeExtensions,
        )
        .kotlinOnly()
    }
  }

  /** Wraps a native generator with optional logging support. */
  private fun wrapNativeGenerator(
    tag: String,
    enableLogging: Boolean,
    factory: (FirSession, CompatContext) -> FirDeclarationGenerationExtension,
  ): (FirSession) -> FirDeclarationGenerationExtension {
    return { session ->
      val logger =
        if (enableLogging) {
          loggerFor(MetroLogger.Type.FirDeclarationGeneration, tag)
        } else {
          MetroLogger.NONE
        }
      if (logger == MetroLogger.NONE) {
        factory(session, compatContext)
      } else {
        LoggingFirDeclarationGenerationExtension(session, logger, factory(session, compatContext))
      }
    }
  }

  private fun loggerFor(type: MetroLogger.Type, tag: String): MetroLogger {
    return if (type in options.enabledLoggers) {
      val reportsDir = options.reportsDir
      val output: (String) -> Unit =
        if (options.reportsEnabled) {
          val outputFile =
            reportsDir.value!!.resolve("fir-${type.name.lowercase()}-$tag.txt").apply {
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

  private fun supertypeGenerator(
    tag: String,
    delegate: ((FirSession, CompatContext) -> FirSupertypeGenerationExtension),
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
          delegate(session, compatContext)
        } else {
          LoggingFirSupertypeGenerationExtension(session, logger, delegate(session, compatContext))
        }
      extension.kotlinOnly()
    }
  }
}

/** Loads external [MetroFirDeclarationGenerationExtension] implementations via ServiceLoader. */
private fun loadExternalDeclarationExtensions(
  session: FirSession,
  options: MetroOptions,
): List<MetroFirDeclarationGenerationExtension> {
  return ServiceLoader.load(
      MetroFirDeclarationGenerationExtension.Factory::class.java,
      MetroFirDeclarationGenerationExtension.Factory::class.java.classLoader,
    )
    .mapNotNull { factory ->
      try {
        factory.create(session, options)
      } catch (e: Exception) {
        // Log but don't fail compilation
        if (options.debug) {
          System.err.println(
            "[Metro] Failed to load external FIR extension from ${factory::class}: ${e.message}"
          )
        }
        null
      }
    }
}

private fun loadExternalContributionExtensions(
  session: FirSession,
  options: MetroOptions,
): List<MetroContributionExtension> {
  return ServiceLoader.load(
      MetroContributionExtension.Factory::class.java,
      MetroContributionExtension.Factory::class.java.classLoader,
    )
    .mapNotNull { factory ->
      try {
        factory.create(session, options)
      } catch (e: Exception) {
        if (options.debug) {
          System.err.println(
            "[Metro] Failed to load external contribution extension from ${factory::class}: ${e.message}"
          )
        }
        null
      }
    }
}
