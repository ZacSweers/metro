// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.CompilerVersionAliases
import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import dev.zacsweers.metro.compiler.tracing.TraceContext
import kotlin.test.Test
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirSession

class ExternalContributionErrorsTest : MetroCompilerTest() {

  @Test
  fun `external graph supertype contribution cannot originate from binding container`() {
    compile(
      source(
        """
        annotation class ExternalContribution

        @BindingContainer
        @ContributesTo(AppScope::class)
        interface ContributedBindingContainer

        @ExternalContribution
        @Origin(ContributedBindingContainer::class)
        interface GeneratedContribution

        @DependencyGraph(AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      compilationBlock = {
        compilerPluginRegistrars =
          listOf(
            ExternalContributionTestRegistrar(
              options = metroOptions,
              loadExternalContributionExtensions = { session, _, _ ->
                listOf(TestOriginContributionExtension(session))
              },
            )
          )
      },
      expectedExitCode = ExitCode.INTERNAL_ERROR,
    ) {
      assertThat(messages)
        .contains(
          "MetroContributionExtension " +
            "dev.zacsweers.metro.compiler.fir.TestOriginContributionExtension returned graph " +
            "supertype contribution test/GeneratedContribution that resolves to " +
            "@BindingContainer test/ContributedBindingContainer."
        )
    }
  }

  private class ExternalContributionTestRegistrar(
    private val options: MetroOptions,
    private val loadExternalContributionExtensions:
      (FirSession, MetroOptions, CompatContext) -> List<MetroContributionExtension>,
  ) : CompilerPluginRegistrar() {
    override val pluginId: String = "dev.zacsweers.metro.compiler"

    override val supportsK2: Boolean
      get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
      if (!options.enabled) return

      val version =
        options.compilerVersion?.let(::KotlinToolingVersion)
          ?: CompatContext.Factory.loadCompilerVersionOrNull()?.let { rawVersion ->
            CompilerVersionAliases.map(rawVersion, options.compilerVersionAliases)
          }
          ?: return

      val compatContext = CompatContext.create(version)
      val classIds = ClassIds.fromOptions(options)
      val traceContext = TraceContext(options)

      with(compatContext) {
        registerFirExtensionCompat(
          MetroFirExtensionRegistrar(
            classIds,
            options,
            isIde = false,
            compatContext,
            traceContext,
            loadExternalContributionExtensions = loadExternalContributionExtensions,
          )
        )
      }
    }
  }
}
