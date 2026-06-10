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
import dev.zacsweers.metro.compiler.MetroOptions
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
import org.jetbrains.kotlin.idea.facet.getMergedCompilerArguments
import org.jetbrains.kotlin.name.ClassId

private const val METRO_PLUGIN_OPTION_PREFIX = "plugin:$PLUGIN_ID:"
private val DEFAULT_METRO_IDE_STATE = MetroIdeModuleState(MetroOptions())

/** Parsed Metro options plus IDE-specific derived caches for one module. */
internal class MetroIdeModuleState(val options: MetroOptions) {
  val annotationClassIds: MetroIdeAnnotationClassIds = MetroIdeAnnotationClassIds(options)
}

/** Lazily caches Metro annotation ClassId groups used by PSI/UAST checks. */
internal class MetroIdeAnnotationClassIds(private val options: MetroOptions) {
  val bindingContainerCallableAnnotations: Set<ClassId> by lazy {
    buildSet {
      addAll(options.bindsAnnotations)
      addAll(options.providesAnnotations)
      addAll(options.multibindsAnnotations)
    }
  }

  val functionAnnotations: Set<ClassId> by lazy {
    buildSet {
      addAll(options.bindsAnnotations)
      addAll(options.providesAnnotations)
      addAll(options.multibindsAnnotations)
      addAll(options.injectAnnotations)
    }
  }

  val constructorInjectionAnnotations: Set<ClassId> by lazy {
    options.injectAnnotations + options.assistedInjectAnnotations
  }

  val providesAnnotations: Set<ClassId>
    get() = options.providesAnnotations

  val bindingContributionAnnotations: Set<ClassId> by lazy {
    buildSet {
      addAll(options.contributesBindingAnnotations)
      addAll(options.contributesIntoSetAnnotations)
      addAll(options.customContributesIntoSetAnnotations)
      addAll(options.contributesIntoMapAnnotations)
    }
  }

  val classLevelInjectionAnnotations: Set<ClassId> by lazy {
    buildSet {
      if (options.contributesAsInject) {
        addAll(bindingContributionAnnotations)
      }
      addAll(options.assistedInjectAnnotations)
    }
  }

  val contributionProviderExclusionAnnotations: Set<ClassId>
    get() = options.contributionProviderExclusionAnnotations
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
  internal fun state(element: PsiElement): MetroIdeModuleState {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return DEFAULT_METRO_IDE_STATE
    return CachedValuesManager.getManager(project).getCachedValue(module) {
      CachedValueProvider.Result.create(
        MetroIdeModuleState(module.metroOptions()),
        KotlinCompilerSettingsTracker.getInstance(project),
      )
    }
  }
}

internal fun PsiElement.metroIdeState(): MetroIdeModuleState {
  return project.service<MetroIdeProjectService>().state(this)
}

/**
 * Reads Metro options from Kotlin's stored compiler plugin option strings.
 *
 * Kotlin's IDE support stores plugin options as `plugin:<plugin-id>:<key>=<value>`.
 *
 * @see org.jetbrains.kotlin.idea.compilerPlugin.modifyCompilerArgumentsForPluginWithFacetSettings
 */
private fun Module.metroOptions(): MetroOptions {
  return KotlinCommonCompilerArgumentsHolder.getMergedCompilerArguments(this)
    .pluginOptions
    .orEmpty()
    .asSequence()
    .filter { it.startsWith(METRO_PLUGIN_OPTION_PREFIX) }
    .map { it.removePrefix(METRO_PLUGIN_OPTION_PREFIX) }
    .mapNotNull { option ->
      val key = option.substringBefore('=', missingDelimiterValue = "")
      val value = option.substringAfter('=', missingDelimiterValue = "")
      if (key.isEmpty()) null else key to value
    }
    .toMap()
    .let { optionsByName ->
      MetroOptions.buildOptions {
        applyRawOptions(optionsByName)
      }
    }
}

private fun <T> Set<T>.emptyUnless(condition: Boolean): Set<T> {
  return if (condition) this else emptySet()
}
