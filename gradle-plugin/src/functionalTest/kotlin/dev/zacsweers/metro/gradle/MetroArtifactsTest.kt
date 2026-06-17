// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("FunctionName")

package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.GradleBuilder.build
import com.google.common.truth.Truth.assertThat
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MetroArtifactsTest {
  @Test
  fun `metroEnv task creates human-readable output`() {
    val fixture =
      object : MetroProject(multiplatform = false) {
        override fun sources() =
          listOf(
            source(
              """
              @DependencyGraph
              interface AppGraph
              """,
              "AppGraph",
            )
          )
      }

    val project = fixture.gradleProject

    build(project.rootDir, "metroEnv")

    val report =
      project.rootDir
        .toPath()
        .resolve("build/reports/metro/env")
        .toFile()
        .walk()
        .single { it.name == "main.txt" }
        .toPath()
    assertTrue(report.exists(), "Metro environment report should exist")

    val content = report.readText()
    assertThat(content).contains("Metro environment report")
    assertThat(content).contains("Project")
    assertThat(content).contains("Versions")
    assertThat(content).contains("Kotlin compiler options")
    assertThat(content).contains("Metro compiler plugin options")
    assertThat(content).contains("  compilation: main")
    assertThat(content).contains("    enabled = true")
    assertThat(content).contains("    reports-destination = ")
  }

  @Test
  fun `diagnosticsRenderMode resolves AUTO plugin-side and explicit values pass through`() {
    val fixture =
      object : MetroProject(multiplatform = false) {
        override fun sources() =
          listOf(
            source(
              """
              @DependencyGraph
              interface AppGraph
              """,
              "AppGraph",
            )
          )
      }

    val project = fixture.gradleProject

    fun envReport(): String =
      project.rootDir
        .toPath()
        .resolve("build/reports/metro/env")
        .toFile()
        .walk()
        .single { it.name == "main.txt" }
        .readText()

    // AUTO + --console=plain resolves to PLAIN before reaching the compiler.
    build(project.rootDir, "metroEnv", "--console=plain")
    assertThat(envReport()).contains("diagnostics-render-mode = PLAIN")

    // IDE-invoked builds (idea.active) resolve AUTO to PLAIN — IDE build output windows don't
    // render ANSI codes.
    build(project.rootDir, "metroEnv", "-Didea.active=true")
    assertThat(envReport()).contains("diagnostics-render-mode = PLAIN")

    // An explicit value skips AUTO resolution entirely.
    build(project.rootDir, "metroEnv", "--console=plain", "-PdiagnosticsRenderMode=RICH")
    assertThat(envReport()).contains("diagnostics-render-mode = RICH")
  }

  @Test
  fun `diagnosticsRenderMode is not a compilation input`() {
    val fixture =
      object : MetroProject(multiplatform = false) {
        override fun sources() =
          listOf(
            source(
              """
              @DependencyGraph
              interface AppGraph
              """,
              "AppGraph",
            )
          )
      }

    val project = fixture.gradleProject

    build(project.rootDir, "compileKotlin", "--console=plain")

    // Render mode is presentation-only; switching it (IDE vs CLI environments) must not
    // invalidate compilation or split build caches.
    val secondBuild = build(project.rootDir, "compileKotlin", "-PdiagnosticsRenderMode=RICH")
    assertThat(secondBuild.task(":compileKotlin")?.outcome)
      .isEqualTo(org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE)
  }

  @Test
  fun `generateMetroGraphMetadata task creates aggregated JSON output`() {
    val testCompilerVersion = getTestCompilerToolingVersion()
    val topLevelFirGenEnabled =
      if (testCompilerVersion.isDev) {
        testCompilerVersion >= KotlinToolingVersion("2.3.20-dev-6204")
      } else {
        testCompilerVersion >= KotlinToolingVersion("2.3.20-Beta1")
      }
    val enableKlibParamsCheck =
      testCompilerVersion >= KotlinToolingVersion("2.3.0") &&
        testCompilerVersion < KotlinToolingVersion("2.3.20-Beta2")
    val generateClassesInIrEnabled = testCompilerVersion >= KotlinToolingVersion("2.4.20-dev-6138")

    val fixture =
      object : MetroProject(multiplatform = false) {
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

    // Run the graph metadata generation task. Plain console keeps the recorded
    // Keep the diagnosticsRenderMode option deterministic (AUTO resolves from console state).
    build(project.rootDir, "generateMetroGraphMetadata", "--console=plain")

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
              "config": {
                "debug": false,
                "enabled": true,
                "generateAssistedFactories": false,
                "enableTopLevelFunctionInjection": $topLevelFirGenEnabled,
                "generateContributionHints": true,
                "generateContributionHintsInFir": ${topLevelFirGenEnabled && !generateClassesInIrEnabled},
                "generateClassesInIr": $generateClassesInIrEnabled,
                "shrinkUnusedBindings": true,
                "statementsPerInitFun": 25,
                "enableGraphSharding": true,
                "keysPerGraphShard": 2000,
                "mergedSupertypeChunkSize": 0,
                "enableSwitchingProviders": false,
                "publicScopedProviderSeverity": "NONE",
                "nonPublicContributionSeverity": "NONE",
                "optionalBindingBehavior": "DEFAULT",
                "warnOnInjectAnnotationPlacement": true,
                "interopAnnotationsNamedArgSeverity": "NONE",
                "unusedGraphInputsSeverity": "WARN",
                "enabledLoggers": [],
                "enableDaggerRuntimeInterop": false,
                "enableGuiceRuntimeInterop": false,
                "maxIrErrorsCount": 20,
                "customProviderTypes": [],
                "customLazyTypes": [],
                "customAssistedAnnotations": [],
                "customAssistedFactoryAnnotations": [],
                "customAssistedInjectAnnotations": [],
                "customBindsAnnotations": [],
                "customContributesToAnnotations": [],
                "customContributesBindingAnnotations": [],
                "customContributesIntoSetAnnotations": [],
                "customGraphExtensionAnnotations": [],
                "customGraphExtensionFactoryAnnotations": [],
                "customElementsIntoSetAnnotations": [],
                "customGraphAnnotations": [],
                "customGraphFactoryAnnotations": [],
                "customInjectAnnotations": [],
                "customIntoMapAnnotations": [],
                "customIntoSetAnnotations": [],
                "customMapKeyAnnotations": [],
                "customMultibindsAnnotations": [],
                "customProvidesAnnotations": [],
                "customQualifierAnnotations": [],
                "customScopeAnnotations": [],
                "customBindingContainerAnnotations": [],
                "enableDaggerAnvilInterop": false,
                "enableFullBindingGraphValidation": false,
                "enableGraphImplClassAsReturnType": false,
                "customOriginAnnotations": [],
                "customOptionalBindingAnnotations": [],
                "contributesAsInject": true,
                "enableKlibParamsCheck": $enableKlibParamsCheck,
                "patchKlibParams": true,
                "forceEnableFirInIde": false,
                "pluginOrderSet": true,
                "compilerVersionAliases": {},
                "parallelThreads": 0,
                "bufferedIcTracking": true,
                "enableProviderInlining": true,
                "enableFunctionProviders": true,
                "desugaredProviderSeverity": "WARN",
                "enableKClassToClassInterop": false,
                "generateContributionProviders": false,
                "enableCircuitCodegen": false,
                "enableHiltInterop": false,
                "diagnosticsRenderMode": "PLAIN",
                "generateStaticAnnotations": true,
                "bindingContributionsAsContainers": true,
                "memberNamingStrategy": "DESCRIPTIVE"
              },
              "stats": {
                "providerFactories": 1,
                "bindsCallables": 0,
                "multibindsCallables": 0,
                "optionalBindings": 0,
                "accessors": 1,
                "injectors": 0,
                "graphExtensionAccessors": 0,
                "graphExtensionFactories": 0,
                "includedGraphs": 0,
                "bindingContainers": 0,
                "dynamicBindings": 0,
                "graphPrivateKeys": 0,
                "publishedBindsKeys": 0,
                "populatedKeys": 2,
                "validatedKeys": 2,
                "reachableKeys": 2,
                "deferredKeys": 0,
                "unusedInputs": 0,
                "providerProperties": 0,
                "scopedProviderProperties": 0,
                "shards": 0,
                "optimizations": {
                  "bindingsPrunedByShrinking": 0,
                  "classConstructorDirectInvocations": 0,
                  "classConstructorNewInstanceCalls": 0,
                  "providerDirectInvocations": 1,
                  "providerNewInstanceCalls": 0,
                  "shardsGenerated": 0,
                  "shardedSupertypes": 0,
                  "shardedInitFunctions": 0,
                  "providerInlines": 0
                }
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
                      "hasDefault": false
                    }
                  ],
                  "isSynthetic": false,
                  "origin": "AppGraph.kt:10:3",
                  "declaration": "provideValue"
                },
                {
                  "key": "test.AppGraph",
                  "bindingKind": "BoundInstance",
                  "isScoped": false,
                  "nameHint": "AppGraphProvider",
                  "dependencies": [],
                  "isSynthetic": false,
                  "origin": "AppGraph.kt:5:1",
                  "declaration": "AppGraph"
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
      object : MetroProject(multiplatform = false) {
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
                "totalBindings": 4,
                "scopedBindings": 0,
                "unscopedBindings": 4,
                "bindingsByKind": {
                  "Alias": 1,
                  "BoundInstance": 1,
                  "ConstructorInjected": 1,
                  "MembersInjected": 1
                },
                "averageDependencies": 0.75,
                "maxDependencies": 1,
                "maxDependenciesBinding": "dev.zacsweers.metro.MembersInjector<test.App>",
                "rootBindings": 1,
                "leafBindings": 1,
                "multibindingCount": 0,
                "aliasCount": 1
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
                "averagePathLength": 2.0,
                "pathLengthDistribution": {
                  "1": 1,
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
                    "key": "test.Context",
                    "bindingKind": "Alias",
                    "dominatedCount": 1,
                    "dominatedKeys": [
                      "test.App"
                    ]
                  },
                  {
                    "key": "dev.zacsweers.metro.MembersInjector<test.App>",
                    "bindingKind": "MembersInjected",
                    "dominatedCount": 0,
                    "dominatedKeys": []
                  },
                  {
                    "key": "test.App",
                    "bindingKind": "BoundInstance",
                    "dominatedCount": 0,
                    "dominatedKeys": []
                  }
                ]
              },
              "centrality": {
                "centralityScores": [
                  {
                    "key": "test.ExampleClass",
                    "bindingKind": "ConstructorInjected",
                    "betweennessCentrality": 2.0,
                    "normalizedCentrality": 1.0
                  },
                  {
                    "key": "test.Context",
                    "bindingKind": "Alias",
                    "betweennessCentrality": 2.0,
                    "normalizedCentrality": 1.0
                  },
                  {
                    "key": "dev.zacsweers.metro.MembersInjector<test.App>",
                    "bindingKind": "MembersInjected",
                    "betweennessCentrality": 0.0,
                    "normalizedCentrality": 0.0
                  },
                  {
                    "key": "test.App",
                    "bindingKind": "BoundInstance",
                    "betweennessCentrality": 0.0,
                    "normalizedCentrality": 0.0
                  }
                ]
              },
              "fanAnalysis": {
                "bindings": [
                  {
                    "key": "dev.zacsweers.metro.MembersInjector<test.App>",
                    "bindingKind": "MembersInjected",
                    "fanIn": 0,
                    "fanOut": 1,
                    "dependents": [],
                    "dependencies": []
                  },
                  {
                    "key": "test.App",
                    "bindingKind": "BoundInstance",
                    "fanIn": 1,
                    "fanOut": 0,
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
                    "fanOut": 0,
                    "dependents": [
                      "test.Context"
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
                      "dev.zacsweers.metro.MembersInjector<test.App>"
                    ],
                    "dependencies": [
                      "test.Context"
                    ]
                  },
                  {
                    "key": "dev.zacsweers.metro.MembersInjector<test.App>",
                    "bindingKind": "MembersInjected",
                    "fanIn": 0,
                    "fanOut": 1,
                    "dependents": [],
                    "dependencies": [
                      "test.ExampleClass"
                    ]
                  }
                ],
                "highFanOut": [
                  {
                    "key": "dev.zacsweers.metro.MembersInjector<test.App>",
                    "bindingKind": "MembersInjected",
                    "fanIn": 0,
                    "fanOut": 1,
                    "dependents": [],
                    "dependencies": [
                      "test.ExampleClass"
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
                      "dev.zacsweers.metro.MembersInjector<test.App>"
                    ],
                    "dependencies": [
                      "test.Context"
                    ]
                  },
                  {
                    "key": "test.App",
                    "bindingKind": "BoundInstance",
                    "fanIn": 1,
                    "fanOut": 0,
                    "dependents": [
                      "test.Context"
                    ],
                    "dependencies": []
                  }
                ],
                "averageFanIn": 0.75,
                "averageFanOut": 0.75
              },
              "pathsToRoot": {
                "rootKey": "",
                "paths": {}
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

  @Test
  fun `reportsDestination directories do not collide across multiplatform targets`() {
    val fixture =
      object : MetroProject(multiplatform = true, reportsEnabled = true) {
        override fun multiplatformTargetsBlock(): String = buildString {
          appendLine("kotlin {")
          appendLine("  jvm()")
          appendLine("  ${KmpTarget.NATIVE_HOST.gradleTargetName}()")
          appendLine("}")
        }

        override fun sources() =
          listOf(
            source(
              """
              @DependencyGraph
              interface AppGraph
              """,
              "AppGraph",
            )
          )
      }

    val project = fixture.gradleProject
    val result = build(project.rootDir, "generateMetroGraphHtml", "--console=plain")

    assertThat(result.task(":generateMetroGraphMetadata")?.outcome)
      .isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
    assertThat(result.task(":analyzeMetroGraph")?.outcome)
      .isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
    assertThat(result.task(":generateMetroGraphHtml")?.outcome)
      .isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)

    val reportingDir = project.rootDir.toPath().resolve("build/tmp/metro/reporting")
    assertTrue(reportingDir.resolve("jvm/main").exists())
    assertTrue(reportingDir.resolve("${KmpTarget.NATIVE_HOST.gradleTargetName}/main").exists())
  }

  @Test
  fun `analysis tasks are skipped when reportsDestination is not present`() {
    val fixture =
      object : MetroProject(multiplatform = true, reportsEnabled = false) {
        override fun multiplatformTargetsBlock(): String = buildString {
          appendLine("kotlin {")
          appendLine("  jvm()")
          appendLine("  ${KmpTarget.NATIVE_HOST.gradleTargetName}()")
          appendLine("}")
        }

        override fun sources() =
          listOf(
            source(
              """
              @DependencyGraph
              interface AppGraph
              """,
              "AppGraph",
            )
          )
      }

    val project = fixture.gradleProject
    val result = build(project.rootDir, "generateMetroGraphHtml", "--console=plain")

    assertThat(result.task(":generateMetroGraphMetadata")?.outcome)
      .isEqualTo(org.gradle.testkit.runner.TaskOutcome.SKIPPED)
    assertThat(result.task(":analyzeMetroGraph")?.outcome)
      .isEqualTo(org.gradle.testkit.runner.TaskOutcome.SKIPPED)
    assertThat(result.task(":generateMetroGraphHtml")?.outcome)
      .isEqualTo(org.gradle.testkit.runner.TaskOutcome.SKIPPED)

    val reportingDir = project.rootDir.toPath().resolve("build/tmp/metro/reporting")
    assertFalse(reportingDir.exists())
  }
}
