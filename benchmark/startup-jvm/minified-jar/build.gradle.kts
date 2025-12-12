// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import java.nio.file.FileSystems
import kotlin.io.path.deleteIfExists
import org.gradle.language.base.plugins.LifecycleBasePlugin.BUILD_GROUP

plugins { alias(libs.plugins.kotlin.jvm) }

// R8 minification infrastructure
val r8Configuration: Configuration by configurations.creating

dependencies {
  // Only used for R8 processing, not exposed transitively
  compileOnly(project(":app:component"))
  r8Configuration("com.android.tools:r8:8.13.17")
}

abstract class BaseR8Task : JavaExec() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val componentJarProp: RegularFileProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val runtimeClasspathProp: ConfigurableFileCollection

  /** Whether to pass runtime classpath as --classpath (library) or as program jars */
  @get:Input abstract val useClasspathForDeps: Property<Boolean>

  fun r8ArgumentProvider(): CommandLineArgumentProvider {
    return CommandLineArgumentProvider {
      buildList {
        addAll(computeArgs())
        val classpathFiles = runtimeClasspathProp.files.filter { it.isFile }
        if (useClasspathForDeps.getOrElse(false) && classpathFiles.isNotEmpty()) {
          // Pass dependencies as --classpath so they're used for analysis only, not included in
          // output
          // Each file needs its own --classpath argument
          classpathFiles.forEach { file ->
            add("--classpath")
            add(file.absolutePath)
          }
        } else {
          // Pass as program jars (included in output)
          classpathFiles.forEach { file -> add(file.absolutePath) }
        }
        add(componentJarProp.get().asFile.absolutePath)
      }
    }
  }

  abstract fun computeArgs(): Iterable<String>

  fun configureR8Inputs(componentJar: Provider<RegularFile>, runtimeClasspath: FileCollection) {
    componentJarProp.set(componentJar)
    runtimeClasspathProp.from(runtimeClasspath)
  }
}

abstract class ExtractR8Rules : BaseR8Task() {
  @get:OutputFile abstract val r8Rules: RegularFileProperty

  override fun computeArgs(): Iterable<String> {
    return buildList {
      add("--rules-output")
      add(r8Rules.get().asFile.absolutePath)
      add("--include-origin-comments")
    }
  }
}

abstract class R8Task : BaseR8Task() {
  @get:Input abstract val javaHome: Property<String>

  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val r8Rules: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val customRules: RegularFileProperty

  @get:OutputFile abstract val mapping: RegularFileProperty

  @get:OutputFile abstract val r8Jar: RegularFileProperty

  override fun computeArgs(): Iterable<String> {
    return buildList {
      add("--classfile")
      add("--output")
      add(r8Jar.get().asFile.absolutePath)
      add("--pg-conf")
      add(r8Rules.get().asFile.absolutePath)
      add("--pg-conf")
      add(customRules.get().asFile.absolutePath)
      add("--pg-map-output")
      add(mapping.get().asFile.absolutePath)
      add("--lib")
      add(javaHome.get())
      // Suppress duplicate resource warnings (META-INF/MANIFEST.MF, module-info.class)
      add("--map-diagnostics:warning:info")
    }
  }
}

val customR8RulesFile = layout.projectDirectory.file("proguard-rules.pro")

// Create a jar from the component's compiled classes
val componentProject = project(":app:component")
val componentJar =
  tasks.register<Jar>("componentJar") {
    group = BUILD_GROUP
    description = "Creates a jar from :app:component compiled classes."

    dependsOn(componentProject.tasks.named("classes"))

    from(componentProject.layout.buildDirectory.dir("classes/kotlin/main"))
    from(componentProject.layout.buildDirectory.dir("classes/java/main"))

    // Exclude duplicate META-INF resources to avoid R8 warnings
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    archiveBaseName.set("component")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
  }

// Get the component project's runtime classpath (all its dependencies)
// This excludes the component project's own build outputs since we're creating our own jar from
// sources
val componentBuildPath = componentProject.layout.buildDirectory.get().asFile.absolutePath
val componentRuntimeClasspath =
  componentProject.configurations.named("runtimeClasspath").get().filter { file ->
    !file.absolutePath.startsWith(componentBuildPath)
  }

val r8RulesExtractTask =
  tasks.register<ExtractR8Rules>("extractR8Rules") {
    group = BUILD_GROUP
    description = "Extracts R8 rules from jars on the classpath."

    inputs.files(r8Configuration)

    classpath(r8Configuration)
    mainClass.set("com.android.tools.r8.ExtractR8Rules")

    r8Rules.set(layout.buildDirectory.file("shrinker/r8.txt"))
    configureR8Inputs(componentJar.flatMap { it.archiveFile }, componentRuntimeClasspath)
    // Extract rules needs all jars as program input
    useClasspathForDeps.set(false)
    argumentProviders += r8ArgumentProvider()
  }

val r8Task =
  tasks.register<R8Task>("r8") {
    group = BUILD_GROUP
    description = "Minifies the :app:component jar with R8."

    inputs.files(r8Configuration)

    classpath(r8Configuration)
    mainClass.set("com.android.tools.r8.R8")

    javaHome.set(providers.systemProperty("java.home"))
    r8Rules.set(r8RulesExtractTask.flatMap { it.r8Rules })
    customRules.set(customR8RulesFile)
    r8Jar.set(layout.buildDirectory.file("libs/${project.name}.jar"))
    mapping.set(layout.buildDirectory.file("libs/${project.name}-mapping.txt"))
    configureR8Inputs(componentJar.flatMap { it.archiveFile }, componentRuntimeClasspath)
    // Include all deps in output (fat jar) - we want to test R8 optimization benefits
    useClasspathForDeps.set(false)
    argumentProviders += r8ArgumentProvider()

    doLast {
      // Work around for https://issuetracker.google.com/issues/134372167
      FileSystems.newFileSystem(r8Jar.get().asFile.toPath(), null as ClassLoader?).use { fs ->
        val root = fs.rootDirectories.first()
        listOf("module-info.class", "META-INF/versions/9/module-info.class").forEach { path ->
          val file = root.resolve(path)
          file.deleteIfExists()
        }
      }
    }
  }

// Disable the default jar task and use R8 output instead
tasks.named<Jar>("jar") { enabled = false }

// Wire the R8 output as the module's primary artifact
artifacts { add("archives", r8Task.flatMap { it.r8Jar }) { builtBy(r8Task) } }

// Make the default configuration use the R8 jar
configurations {
  named("runtimeElements") {
    outgoing {
      artifacts.clear()
      artifact(r8Task.flatMap { it.r8Jar }) { builtBy(r8Task) }
    }
  }
  named("apiElements") {
    outgoing {
      artifacts.clear()
      artifact(r8Task.flatMap { it.r8Jar }) { builtBy(r8Task) }
    }
  }
}
