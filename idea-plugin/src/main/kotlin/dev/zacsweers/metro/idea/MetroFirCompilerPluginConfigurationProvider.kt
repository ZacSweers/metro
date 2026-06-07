// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
import org.jetbrains.kotlin.idea.facet.getMergedCompilerArguments
import org.jetbrains.kotlin.idea.fir.extensions.KotlinFirCompilerPluginConfigurationForIdeProvider

internal val METRO_ENABLED_OPTION_KEY = CompilerConfigurationKey<Boolean>("enabled")
private const val ENABLED_OPTION_NAME = "enabled"
private const val METRO_PLUGIN_OPTION_PREFIX = "plugin:$PLUGIN_ID:"

/**
 * Metro compiler options understood by the IDE plugin without loading Metro's compiler artifact.
 */
internal data class MetroIdeOptions(val enabled: Boolean = true) {
  companion object {
    fun load(configuration: CompilerConfiguration): MetroIdeOptions {
      return MetroIdeOptions(enabled = configuration.get(METRO_ENABLED_OPTION_KEY, true))
    }

    fun load(module: Module): MetroIdeOptions {
      return MetroIdeOptions(
        enabled = module.metroPluginOption(ENABLED_OPTION_NAME)?.toBooleanStrict() ?: true
      )
    }
  }
}

/**
 * Reads and caches Metro compiler options that IDE features should honor for a project.
 *
 * Options are cached per module because implicit usage checks can run frequently while
 * highlighting.
 *
 * @see org.jetbrains.kotlin.idea.facet.getMergedCompilerArguments
 * @see org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
 */
@Service(Service.Level.PROJECT)
class MetroIdeProjectService(private val project: Project) {

  fun isMetroEnabled(element: PsiElement): Boolean {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return true
    return CachedValuesManager.getManager(project)
      .getCachedValue(module) {
        CachedValueProvider.Result.create(
          MetroIdeOptions.load(module),
          KotlinCompilerSettingsTracker.getInstance(project),
        )
      }
      .enabled
  }
}

internal fun PsiElement.isMetroEnabled(): Boolean {
  return project.service<MetroIdeProjectService>().isMetroEnabled(this)
}

/**
 * Supplies IDE-side Kotlin compiler plugin configuration for Metro projects.
 *
 * This provider keeps the IDE plugin independent of Metro's compiler artifact while reading the
 * small set of Metro compiler options the IDE plugin understands.
 */
@OptIn(ExperimentalCompilerApi::class)
class MetroFirCompilerPluginConfigurationProvider :
  KotlinFirCompilerPluginConfigurationForIdeProvider {

  override fun provideCompilerConfigurationWithCustomOptions(
    original: CompilerConfiguration
  ): CompilerConfiguration {
    val options = MetroIdeOptions.load(original)
    return original.copy().also { configuration ->
      configuration.put(METRO_ENABLED_OPTION_KEY, options.enabled)
    }
  }

  override fun isConfigurationProviderForCompilerPlugin(
    registrar: CompilerPluginRegistrar
  ): Boolean = registrar.pluginId == PLUGIN_ID
}

/**
 * Reads Metro options from Kotlin's stored compiler plugin option strings.
 *
 * Kotlin's IDE support stores plugin options as `plugin:<plugin-id>:<key>=<value>`.
 *
 * @see org.jetbrains.kotlin.idea.compilerPlugin.modifyCompilerArgumentsForPluginWithFacetSettings
 */
private fun Module.metroPluginOption(optionName: String): String? {
  val prefix = "$METRO_PLUGIN_OPTION_PREFIX$optionName="
  return KotlinCommonCompilerArgumentsHolder.getMergedCompilerArguments(this)
    .pluginOptions
    ?.lastOrNull { it.startsWith(prefix) }
    ?.removePrefix(prefix)
}
