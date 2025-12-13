// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("FunctionName")

package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.GradleBuilder.build
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

class MetroArtifactsTest {
  @Test
  fun `generateMetroGraphMetadata task creates aggregated JSON output`() {
    val fixture =
      object : MetroProject() {
        override fun sources() =
          listOf(
            source(
              """
              @DependencyGraph
              interface AppGraph {
                val value: String

                @Provides
                fun provideValue(): String = "test"
              }
              """,
              "AppGraph",
            )
          )
      }

    val project = fixture.gradleProject

    // Run the graph metadata generation task
    build(project.rootDir, "generateMetroGraphMetadata")

    val metadataFile = File(project.rootDir, "build/reports/metro/graphMetadata.json")
    assertTrue(metadataFile.exists(), "Aggregated graph metadata file should exist")

    // TODO add more example outputs here. This'll probably churn a bit
    val content = metadataFile.readText()
    assertThat(content)
      .isEqualTo(
        // language=JSON
        """
        {
          "projectPath": ":",
          "graphCount": 1,
          "graphs": [
            {
              "graph": "test.AppGraph",
              "scopes": [],
              "aggregationScopes": [],
              "roots": {
                "accessors": [
                  {
                    "key": "kotlin.String",
                    "isDeferrable": false
                  }
                ],
                "injectors": []
              },
              "extensions": {
                "accessors": [],
                "factoryAccessors": [],
                "factoriesImplemented": []
              },
              "bindings": [
                {
                  "key": "kotlin.String",
                  "bindingKind": "Provided",
                  "isScoped": false,
                  "nameHint": "provideValue",
                  "dependencies": [
                    {
                      "key": "test.AppGraph",
                      "hasDefault": false,
                      "isAssisted": false
                    }
                  ],
                  "isSynthetic": false,
                  "origin": "AppGraph.kt:11:3",
                  "declaration": "provideValue",
                  "multibinding": null,
                  "optionalWrapper": null
                },
                {
                  "key": "test.AppGraph",
                  "bindingKind": "BoundInstance",
                  "isScoped": false,
                  "nameHint": "AppGraphProvider",
                  "dependencies": [],
                  "isSynthetic": false,
                  "origin": "AppGraph.kt:6:1",
                  "declaration": "AppGraph",
                  "multibinding": null,
                  "optionalWrapper": null
                },
                {
                  "key": "test.AppGraph.Impl",
                  "bindingKind": "Alias",
                  "isScoped": false,
                  "nameHint": "Impl",
                  "dependencies": [
                    {
                      "key": "test.AppGraph",
                      "hasDefault": false,
                      "isAssisted": false
                    }
                  ],
                  "isSynthetic": true,
                  "multibinding": null,
                  "optionalWrapper": null,
                  "aliasTarget": "test.AppGraph"
                }
              ]
            }
          ]
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun `analyzeMetroGraph task succeed with cycles`() {
    val fixture =
      object : MetroProject() {
        override fun sources() =
          listOf(
            source(
              """
              class App : Context {
                @Inject lateinit var exampleClass: ExampleClass
              }

              interface Context
              
              @DependencyGraph
              interface AppGraph {
              
                @Binds val App.bindContext: Context
              
                fun inject(app: App)
              
                @DependencyGraph.Factory
                fun interface Factory {
                  fun create(@Provides app: App): AppGraph
                }
              }
              
              @Inject
              class ExampleClass(context: Context)
              """,
              "AppGraph",
            )
          )
      }

    val project = fixture.gradleProject

    // Run the graph analysis task
    build(project.rootDir, "analyzeMetroGraph")

    val analysisFile = File(project.rootDir, "build/reports/metro/analysis.json")
    assertTrue(analysisFile.exists(), "Graph analysis file should exist")

    val content = analysisFile.readText()
    //TODO validate the analysis output?
  }
}
