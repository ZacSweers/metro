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
    SubpluginArtifact(
      groupId = "dev.zacsweers.lattice",
      artifactId = "compiler",
      version = VERSION,
    )

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

    val enabled = extension.enabled.get()
    val debug = extension.debug.get()

    return project.provider {
      listOf(
        SubpluginOption(key = "enabled", value = enabled.toString()),
        SubpluginOption(key = "debug", value = debug.toString()),
      )
    }
  }
}
