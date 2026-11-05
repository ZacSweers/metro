package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency
import com.autonomousapps.kit.gradle.Dependency.Companion.implementation
import com.autonomousapps.kit.Source
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.getTestDaggerVersion
import dev.zacsweers.metro.gradle.source
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DaggerMultiModuleICTests(target: KmpTarget) : BaseIncrementalCompilationTest(target) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun targets(): List<KmpTarget> = KmpTarget.selectedTargets()
    }

    @Test
    fun multiModuleShouldIncrementallyCompile() {
        val dagger = implementation("com.google.dagger:dagger:${getTestDaggerVersion()}")
        val fixture = object : MetroProject(
                additionalGradleProperties =
                    listOf("kotlin.build.report.output=file", "org.gradle.caching=false"),
            ) {
            override fun StringBuilder.onBuildScript() {
                appendLine(
                    """
                metro {
                  interop { includeDagger() }
                }
                """.trimIndent(),
                )
            }

            override fun buildGradleProject() = multiModuleProject {
                root {
                    sources(appGraph)
                    dependencies(dagger, Dependency.implementation(":lib"))
                }
                subproject("lib") {
                    sources(bindingContainer, targetImpl)
                    dependencies(dagger)
                }
            }

            private val appGraph =
                source(
                    """
                            @Singleton
                            @Component(modules = [TargetBindingModule::class])
                            interface AppGraph {
                              val target: ITarget
                            }
                            """
                        .trimIndent(),
                    extraImports = arrayOf("javax.inject.Singleton", "dagger.Component"),
                )

            private val bindingContainer =
                source(
                    """
                        interface ITarget
            
                        @Module
                        interface TargetBindingModule {
                          @Binds
                          fun bindsTarget(impl: Target): ITarget
                        }
                        """
                        .trimIndent(),
                    extraImports = arrayOf("dagger.Module", "dagger.Binds"),
                )

            val targetImpl = source(
                """
                            class Target @Inject constructor() : ITarget
                            """
                    .trimIndent(),
                extraImports = arrayOf("javax.inject.Inject"),
            )

        }
        val project = fixture.gradleProject
        val libProject = project.subprojects.first { it.name == "lib" }
        val reportsDir = project.rootDir.resolve("build/reports/kotlin-build")
        val libCompileTask = compileTaskFor("lib")

        // First build should succeed with unscoped Target
        val firstBuildResult = project.compileKotlin()
        assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val reportsAfterFirstBuild = reportsDir.listFiles().orEmpty().toSet()

        // Add scope annotation to the Target class in the lib module
        libProject.modify(
            project.rootDir,
            fixture.targetImpl,
            """
      
                  class Target @Inject constructor() : ITarget {
                    // adding new function here. Since this is non-ABI change, incremental compilation should work fine.
                    private fun test() { }
                  }
                  """
                .trimIndent(),
        )

        // Verify the lib compile was incremental — only the modified file should be recompiled
        val secondBuildResult = project.compileKotlin()
        assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val newReport = reportsDir.listFiles().orEmpty().single { it !in reportsAfterFirstBuild }
        assertThat(newReport.readText()).doesNotContain("Non-incremental compilation will be performed")
        assertThat(compiledSourceNames(newReport, libCompileTask)).containsExactly("Target.kt")
    }

    @Test
    fun multiModuleScopingChangesOnBoundClass() {
        val dagger = implementation("com.google.dagger:dagger:${getTestDaggerVersion()}")
        val fixture = object : MetroProject(
            additionalGradleProperties =
                listOf("kotlin.build.report.output=file", "org.gradle.caching=false"),
        ) {
            override fun StringBuilder.onBuildScript() {
                appendLine(
                    """
                metro {
                  interop { includeDagger() }
                }
                """.trimIndent(),
                )
            }

            override fun buildGradleProject() = multiModuleProject {
                root {
                    sources(appGraph)
                    dependencies(dagger, Dependency.implementation(":lib"))
                }
                subproject("lib") {
                    sources(bindingContainer, targetImpl)
                    dependencies(dagger)
                }
            }

            private val appGraph =
                source(
                    """
                            @Singleton
                            @Component(modules = [TargetBindingModule::class])
                            interface AppGraph {
                              val target: ITarget
                            }
                            """
                        .trimIndent(),
                    extraImports = arrayOf("javax.inject.Singleton", "dagger.Component"),
                )

            private val bindingContainer =
                source(
                    """
                        interface ITarget
            
                        @Module
                        interface TargetBindingModule {
                          @Binds
                          fun bindsTarget(impl: Target): ITarget
                        }
                        """
                        .trimIndent(),
                    extraImports = arrayOf("dagger.Module", "dagger.Binds"),
                )

            val targetImpl = source(
                """
                            class Target @Inject constructor() : ITarget
                            """
                    .trimIndent(),
                extraImports = arrayOf("javax.inject.Inject"),
            )

        }
        val project = fixture.gradleProject
        val libProject = project.subprojects.first { it.name == "lib" }

        // First build should succeed with unscoped Target
        val firstBuildResult = project.compileKotlin()
        assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(project.asMetroProject.appGraphReports.scopedProviderPropertyKeys)
            .doesNotContain("test.Target")

        // Add scope annotation to the Target class in the lib module
        libProject.modify(
            project.rootDir,
            fixture.targetImpl,
            """
      
                  @javax.inject.Singleton
                  class Target @Inject constructor() : ITarget 
                  """
                .trimIndent(),
        )

        // Second build should have the scope
        val secondBuildResult = project.compileKotlin()
        assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(project.asMetroProject.appGraphReports.scopedProviderPropertyKeys)
            .contains("test.Target")

    }


}