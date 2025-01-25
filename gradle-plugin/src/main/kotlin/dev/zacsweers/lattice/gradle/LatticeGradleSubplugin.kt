/*
 * Copyright (C) 2021 Zac Sweers
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
package dev.zacsweers.lattice.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

public class LatticeGradleSubplugin : KotlinCompilerPluginSupportPlugin {

  override fun apply(target: Project) {
    target.extensions.create("lattice", LatticePluginExtension::class.java)
  }

  override fun getCompilerPluginId(): String = PLUGIN_ID

  override fun getPluginArtifact(): SubpluginArtifact =
    SubpluginArtifact(groupId = "dev.zacsweers.lattice", artifactId = "compiler", version = VERSION)

  override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

  override fun applyToCompilation(
    kotlinCompilation: KotlinCompilation<*>
  ): Provider<List<SubpluginOption>> {
    val project = kotlinCompilation.target.project
    val extension = project.extensions.getByType(LatticePluginExtension::class.java)

    project.dependencies.add(
      kotlinCompilation.implementationConfigurationName,
      "dev.zacsweers.lattice:runtime:${VERSION}",
    )

    return project.provider {
      buildList {
        SubpluginOption(key = "enabled", value = extension.enabled.get().toString())
        SubpluginOption(key = "debug", value = extension.debug.get().toString())

        with(extension.customAnnotations) {
          assisted
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-assisted", value = it.joinToString(":")) }
          assistedFactory
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-assisted-factory", value = it.joinToString(":")) }
          assistedInject
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-assisted-inject", value = it.joinToString(":")) }
          binds
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-binds", value = it.joinToString(":")) }
          bindsInstance
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-binds-instance", value = it.joinToString(":")) }
          contributesTo
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-contributes-to", value = it.joinToString(":")) }
          contributesBinding
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-contributes-binding", value = it.joinToString(":")) }
          elementsIntoSet
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-elements-into-set", value = it.joinToString(":")) }
          graph
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-graph", value = it.joinToString(":")) }
          graphFactory
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-graph-factory", value = it.joinToString(":")) }
          inject
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-inject", value = it.joinToString(":")) }
          intoMap
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-into-map", value = it.joinToString(":")) }
          intoSet
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-into-set", value = it.joinToString(":")) }
          mapKey
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-map-key", value = it.joinToString(":")) }
          multibinds
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-multibinds", value = it.joinToString(":")) }
          provides
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-provides", value = it.joinToString(":")) }
          qualifier
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-qualifier", value = it.joinToString(":")) }
          scope
            .getOrElse(emptySet())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-scope", value = it.joinToString(":")) }
        }
      }
    }
  }
}
