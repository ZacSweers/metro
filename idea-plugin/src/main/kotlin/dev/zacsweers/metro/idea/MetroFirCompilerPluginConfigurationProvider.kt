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
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
import org.jetbrains.kotlin.idea.facet.getMergedCompilerArguments
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

private const val ENABLED_OPTION_NAME = "enabled"
private const val CUSTOM_ASSISTED_INJECT_OPTION_NAME = "custom-assisted-inject"
private const val CUSTOM_BINDS_OPTION_NAME = "custom-binds"
private const val CUSTOM_INJECT_OPTION_NAME = "custom-inject"
private const val CUSTOM_MULTIBINDS_OPTION_NAME = "custom-multibinds"
private const val CUSTOM_PROVIDES_OPTION_NAME = "custom-provides"
private const val INTEROP_INCLUDE_ANVIL_ANNOTATIONS_OPTION_NAME =
  "interop-include-anvil-annotations"
private const val INTEROP_INCLUDE_DAGGER_ANNOTATIONS_OPTION_NAME =
  "interop-include-dagger-annotations"
private const val INTEROP_INCLUDE_GUICE_ANNOTATIONS_OPTION_NAME =
  "interop-include-guice-annotations"
private const val INTEROP_INCLUDE_HILT_ANNOTATIONS_OPTION_NAME = "interop-include-hilt-annotations"
private const val INTEROP_INCLUDE_JAKARTA_ANNOTATIONS_OPTION_NAME =
  "interop-include-jakarta-annotations"
private const val INTEROP_INCLUDE_JAVAX_ANNOTATIONS_OPTION_NAME =
  "interop-include-javax-annotations"
private const val INTEROP_INCLUDE_KOTLIN_INJECT_ANNOTATIONS_OPTION_NAME =
  "interop-include-kotlin-inject-annotations"
private const val INTEROP_INCLUDE_KOTLIN_INJECT_ANVIL_ANNOTATIONS_OPTION_NAME =
  "interop-include-kotlin-inject-anvil-annotations"
private const val METRO_PLUGIN_OPTION_PREFIX = "plugin:$PLUGIN_ID:"
internal val METRO_PACKAGE = FqName("dev.zacsweers.metro")
private val METRO_BINDS_ANNOTATIONS = setOf(METRO_PACKAGE.child("Binds"))
private val METRO_PROVIDES_ANNOTATIONS = setOf(METRO_PACKAGE.child("Provides"))
private val METRO_MULTIBINDS_ANNOTATIONS = setOf(METRO_PACKAGE.child("Multibinds"))
private val METRO_INJECT_ANNOTATIONS = setOf(METRO_PACKAGE.child("Inject"))
private val METRO_ASSISTED_INJECT_ANNOTATIONS = setOf(METRO_PACKAGE.child("AssistedInject"))
private val DAGGER_BINDS_ANNOTATIONS = setOf(FqName("dagger.Binds"))
private val DAGGER_PROVIDES_ANNOTATIONS =
  setOf(FqName("dagger.Provides"), FqName("dagger.BindsInstance"))
private val DAGGER_MULTIBINDS_ANNOTATIONS = setOf(FqName("dagger.multibindings.Multibinds"))
private val DAGGER_ASSISTED_INJECT_ANNOTATIONS = setOf(FqName("dagger.assisted.AssistedInject"))
private val JAKARTA_INJECT_ANNOTATIONS = setOf(FqName("jakarta.inject.Inject"))
private val JAVAX_INJECT_ANNOTATIONS = setOf(FqName("javax.inject.Inject"))
private val KOTLIN_INJECT_ANNOTATIONS = setOf(FqName("me.tatarka.inject.annotations.Inject"))
private val KOTLIN_INJECT_PROVIDES_ANNOTATIONS =
  setOf(FqName("me.tatarka.inject.annotations.Provides"))
private val GUICE_INJECT_ANNOTATIONS = setOf(FqName("com.google.inject.Inject"))
private val GUICE_PROVIDES_ANNOTATIONS = setOf(FqName("com.google.inject.Provides"))
private val GUICE_ASSISTED_INJECT_ANNOTATIONS =
  setOf(FqName("com.google.inject.assistedinject.AssistedInject"))

/**
 * Metro compiler options understood by the IDE plugin without loading Metro's compiler artifact.
 */
internal data class MetroIdeOptions(
  val enabled: Boolean = true,
  val bindsAnnotations: Set<FqName> = METRO_BINDS_ANNOTATIONS,
  val providesAnnotations: Set<FqName> = METRO_PROVIDES_ANNOTATIONS,
  val multibindsAnnotations: Set<FqName> = METRO_MULTIBINDS_ANNOTATIONS,
  val injectAnnotations: Set<FqName> = METRO_INJECT_ANNOTATIONS,
  val assistedInjectAnnotations: Set<FqName> = METRO_ASSISTED_INJECT_ANNOTATIONS,
) {
  val moduleDeclarationAnnotations: Set<FqName> =
    bindsAnnotations + providesAnnotations + multibindsAnnotations
  val functionDeclarationAnnotations: Set<FqName> = moduleDeclarationAnnotations + injectAnnotations
  val constructorInjectionAnnotations: Set<FqName> = injectAnnotations + assistedInjectAnnotations

  companion object {
    fun load(module: Module): MetroIdeOptions {
      val options = module.metroPluginOptions()
      val includeDagger =
        options.boolean(INTEROP_INCLUDE_DAGGER_ANNOTATIONS_OPTION_NAME) ||
          options.boolean(INTEROP_INCLUDE_ANVIL_ANNOTATIONS_OPTION_NAME) ||
          options.boolean(INTEROP_INCLUDE_HILT_ANNOTATIONS_OPTION_NAME)
      val includeKotlinInject =
        options.boolean(INTEROP_INCLUDE_KOTLIN_INJECT_ANNOTATIONS_OPTION_NAME) ||
          options.boolean(INTEROP_INCLUDE_KOTLIN_INJECT_ANVIL_ANNOTATIONS_OPTION_NAME)
      val includeGuice = options.boolean(INTEROP_INCLUDE_GUICE_ANNOTATIONS_OPTION_NAME)
      val includeJakarta =
        options.boolean(INTEROP_INCLUDE_JAKARTA_ANNOTATIONS_OPTION_NAME) ||
          includeDagger ||
          includeGuice
      val includeJavax =
        options.boolean(INTEROP_INCLUDE_JAVAX_ANNOTATIONS_OPTION_NAME) || includeDagger
      return MetroIdeOptions(
        enabled = options.boolean(ENABLED_OPTION_NAME, defaultValue = true),
        bindsAnnotations =
          METRO_BINDS_ANNOTATIONS +
            options.fqNames(CUSTOM_BINDS_OPTION_NAME) +
            DAGGER_BINDS_ANNOTATIONS.emptyUnless(includeDagger),
        providesAnnotations =
          METRO_PROVIDES_ANNOTATIONS +
            options.fqNames(CUSTOM_PROVIDES_OPTION_NAME) +
            DAGGER_PROVIDES_ANNOTATIONS.emptyUnless(includeDagger) +
            KOTLIN_INJECT_PROVIDES_ANNOTATIONS.emptyUnless(includeKotlinInject) +
            GUICE_PROVIDES_ANNOTATIONS.emptyUnless(includeGuice),
        multibindsAnnotations =
          METRO_MULTIBINDS_ANNOTATIONS +
            options.fqNames(CUSTOM_MULTIBINDS_OPTION_NAME) +
            DAGGER_MULTIBINDS_ANNOTATIONS.emptyUnless(includeDagger),
        injectAnnotations =
          METRO_INJECT_ANNOTATIONS +
            options.fqNames(CUSTOM_INJECT_OPTION_NAME) +
            JAVAX_INJECT_ANNOTATIONS.emptyUnless(includeJavax) +
            JAKARTA_INJECT_ANNOTATIONS.emptyUnless(includeJakarta) +
            KOTLIN_INJECT_ANNOTATIONS.emptyUnless(includeKotlinInject) +
            GUICE_INJECT_ANNOTATIONS.emptyUnless(includeGuice),
        assistedInjectAnnotations =
          METRO_ASSISTED_INJECT_ANNOTATIONS +
            options.fqNames(CUSTOM_ASSISTED_INJECT_OPTION_NAME) +
            DAGGER_ASSISTED_INJECT_ANNOTATIONS.emptyUnless(includeDagger) +
            GUICE_ASSISTED_INJECT_ANNOTATIONS.emptyUnless(includeGuice),
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
  internal fun options(element: PsiElement): MetroIdeOptions {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return MetroIdeOptions()
    return CachedValuesManager.getManager(project).getCachedValue(module) {
      CachedValueProvider.Result.create(
        MetroIdeOptions.load(module),
        KotlinCompilerSettingsTracker.getInstance(project),
      )
    }
  }
}

internal fun PsiElement.metroIdeOptions(): MetroIdeOptions {
  return project.service<MetroIdeProjectService>().options(this)
}

/**
 * Reads Metro options from Kotlin's stored compiler plugin option strings.
 *
 * Kotlin's IDE support stores plugin options as `plugin:<plugin-id>:<key>=<value>`.
 *
 * @see org.jetbrains.kotlin.idea.compilerPlugin.modifyCompilerArgumentsForPluginWithFacetSettings
 */
private fun Module.metroPluginOptions(): MetroPluginOptions {
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
    .let(::MetroPluginOptions)
}

private class MetroPluginOptions(private val valuesByName: Map<String, String>) {

  fun boolean(optionName: String, defaultValue: Boolean = false): Boolean {
    return valuesByName[optionName]?.toBooleanStrict() ?: defaultValue
  }

  fun fqNames(optionName: String): Set<FqName> {
    return valuesByName[optionName]
      ?.splitToSequence(':')
      ?.filter(String::isNotBlank)
      ?.map { ClassId.fromString(it, false).asSingleFqName() }
      ?.toSet()
      .orEmpty()
  }
}

private fun FqName.child(name: String): FqName {
  return FqName("${asString()}.$name")
}

private fun <T> Set<T>.emptyUnless(condition: Boolean): Set<T> {
  return if (condition) this else emptySet()
}
