// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("FunctionName")

package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.GradleBuilder.build
import com.google.common.truth.Truth.assertThat
import kotlin.io.path.exists
import kotlin.io.path.readText
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
    val reports = AnalysisReports.from(project.rootDir)

    // Run the graph metadata generation task
    build(project.rootDir, "generateMetroGraphMetadata")

    val metadataFile = reports.graphMetadataFile
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
  fun `analyzeMetroGraph task for graph with just injectors`() {
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
    val reports = AnalysisReports.from(project.rootDir)

    // Run the graph analysis task
    build(project.rootDir, "analyzeMetroGraph")

    val analysisFile = reports.analysisFile
    assertTrue(analysisFile.exists(), "Graph analysis file should exist")

    val content = analysisFile.readText()

    assertThat(content)
      .isEqualTo(
        // language=JSON
        """
        {
          "projectPath": ":",
          "graphs": [
            {
              "graphName": "test.AppGraph",
              "statistics": {
                "totalBindings": 5,
                "scopedBindings": 0,
                "unscopedBindings": 5,
                "bindingsByKind": {
                  "Alias": 2,
                  "BoundInstance": 2,
                  "ConstructorInjected": 1
                },
                "averageDependencies": 0.8,
                "maxDependencies": 1,
                "maxDependenciesBinding": "test.App",
                "rootBindings": 1,
                "leafBindings": 1,
                "multibindingCount": 0,
                "aliasCount": 2
              },
              "longestPath": {
                "longestPathLength": 3,
                "longestPaths": [
                  [
                    "test.ExampleClass",
                    "test.Context",
                    "test.App"
                  ]
                ],
                "averagePathLength": 2.5,
                "pathLengthDistribution": {
                  "2": 1,
                  "3": 1
                }
              },
              "dominator": {
                "dominators": [
                  {
                    "key": "test.ExampleClass",
                    "bindingKind": "ConstructorInjected",
                    "dominatedCount": 2,
                    "dominatedKeys": [
                      "test.App",
                      "test.Context"
                    ]
                  },
                  {
                    "key": "test.AppGraph.Impl",
                    "bindingKind": "Alias",
                    "dominatedCount": 1,
                    "dominatedKeys": [
                      "test.AppGraph"
                    ]
                  },
                  {
                    "key": "test.Context",
                    "bindingKind": "Alias",
                    "dominatedCount": 1,
                    "dominatedKeys": [
                      "test.App"
                    ]
                  },
                  {
                    "key": "test.App",
                    "bindingKind": "BoundInstance",
                    "dominatedCount": 0,
                    "dominatedKeys": []
                  },
                  {
                    "key": "test.AppGraph",
                    "bindingKind": "BoundInstance",
                    "dominatedCount": 0,
                    "dominatedKeys": []
                  }
                ]
              },
              "centrality": {
                "centralityScores": [
                  {
                    "key": "test.App",
                    "bindingKind": "BoundInstance",
                    "betweennessCentrality": 1.0,
                    "normalizedCentrality": 1.0
                  },
                  {
                    "key": "test.ExampleClass",
                    "bindingKind": "ConstructorInjected",
                    "betweennessCentrality": 1.0,
                    "normalizedCentrality": 1.0
                  },
                  {
                    "key": "test.Context",
                    "bindingKind": "Alias",
                    "betweennessCentrality": 1.0,
                    "normalizedCentrality": 1.0
                  },
                  {
                    "key": "test.AppGraph.Impl",
                    "bindingKind": "Alias",
                    "betweennessCentrality": 0.0,
                    "normalizedCentrality": 0.0
                  },
                  {
                    "key": "test.AppGraph",
                    "bindingKind": "BoundInstance",
                    "betweennessCentrality": 0.0,
                    "normalizedCentrality": 0.0
                  }
                ]
              },
              "fanAnalysis": {
                "bindings": [
                  {
                    "key": "test.App",
                    "bindingKind": "BoundInstance",
                    "fanIn": 1,
                    "fanOut": 1,
                    "dependents": [],
                    "dependencies": []
                  },
                  {
                    "key": "test.AppGraph",
                    "bindingKind": "BoundInstance",
                    "fanIn": 1,
                    "fanOut": 0,
                    "dependents": [],
                    "dependencies": []
                  },
                  {
                    "key": "test.AppGraph.Impl",
                    "bindingKind": "Alias",
                    "fanIn": 0,
                    "fanOut": 1,
                    "dependents": [],
                    "dependencies": []
                  },
                  {
                    "key": "test.Context",
                    "bindingKind": "Alias",
                    "fanIn": 1,
                    "fanOut": 1,
                    "dependents": [],
                    "dependencies": []
                  },
                  {
                    "key": "test.ExampleClass",
                    "bindingKind": "ConstructorInjected",
                    "fanIn": 1,
                    "fanOut": 1,
                    "dependents": [],
                    "dependencies": []
                  }
                ],
                "highFanIn": [
                  {
                    "key": "test.App",
                    "bindingKind": "BoundInstance",
                    "fanIn": 1,
                    "fanOut": 1,
                    "dependents": [
                      "test.Context"
                    ],
                    "dependencies": [
                      "test.ExampleClass"
                    ]
                  },
                  {
                    "key": "test.AppGraph",
                    "bindingKind": "BoundInstance",
                    "fanIn": 1,
                    "fanOut": 0,
                    "dependents": [
                      "test.AppGraph.Impl"
                    ],
                    "dependencies": []
                  },
                  {
                    "key": "test.Context",
                    "bindingKind": "Alias",
                    "fanIn": 1,
                    "fanOut": 1,
                    "dependents": [
                      "test.ExampleClass"
                    ],
                    "dependencies": [
                      "test.App"
                    ]
                  },
                  {
                    "key": "test.ExampleClass",
                    "bindingKind": "ConstructorInjected",
                    "fanIn": 1,
                    "fanOut": 1,
                    "dependents": [
                      "test.App"
                    ],
                    "dependencies": [
                      "test.Context"
                    ]
                  },
                  {
                    "key": "test.AppGraph.Impl",
                    "bindingKind": "Alias",
                    "fanIn": 0,
                    "fanOut": 1,
                    "dependents": [],
                    "dependencies": [
                      "test.AppGraph"
                    ]
                  }
                ],
                "highFanOut": [
                  {
                    "key": "test.App",
                    "bindingKind": "BoundInstance",
                    "fanIn": 1,
                    "fanOut": 1,
                    "dependents": [
                      "test.Context"
                    ],
                    "dependencies": [
                      "test.ExampleClass"
                    ]
                  },
                  {
                    "key": "test.AppGraph.Impl",
                    "bindingKind": "Alias",
                    "fanIn": 0,
                    "fanOut": 1,
                    "dependents": [],
                    "dependencies": [
                      "test.AppGraph"
                    ]
                  },
                  {
                    "key": "test.Context",
                    "bindingKind": "Alias",
                    "fanIn": 1,
                    "fanOut": 1,
                    "dependents": [
                      "test.ExampleClass"
                    ],
                    "dependencies": [
                      "test.App"
                    ]
                  },
                  {
                    "key": "test.ExampleClass",
                    "bindingKind": "ConstructorInjected",
                    "fanIn": 1,
                    "fanOut": 1,
                    "dependents": [
                      "test.App"
                    ],
                    "dependencies": [
                      "test.Context"
                    ]
                  },
                  {
                    "key": "test.AppGraph",
                    "bindingKind": "BoundInstance",
                    "fanIn": 1,
                    "fanOut": 0,
                    "dependents": [
                      "test.AppGraph.Impl"
                    ],
                    "dependencies": []
                  }
                ],
                "averageFanIn": 0.8,
                "averageFanOut": 0.8
              },
              "pathsToRoot": {
                "rootKey": "test.AppGraph",
                "paths": {
                  "test.App": [],
                  "test.AppGraph": [
                    "test.AppGraph"
                  ],
                  "test.AppGraph.Impl": [],
                  "test.Context": [],
                  "test.ExampleClass": []
                }
              }
            }
          ]
        }
        """
          .trimIndent()
      )

    build(project.rootDir, "generateMetroGraphHtml")

    val htmlFile = reports.htmlFileForGraph("test.AppGraph")
    assertTrue(htmlFile.exists(), "Graph HTML file should exist")
  }
}
