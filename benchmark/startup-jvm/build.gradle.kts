// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import java.nio.file.FileSystems
import kotlin.io.path.deleteIfExists
import org.gradle.language.base.plugins.LifecycleBasePlugin.BUILD_GROUP
import org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.jmh)
}

dependencies { jmh(project(":app:component")) }

jmh {
  warmupIterations = 4
  iterations = 10
  fork = 2
  resultFormat = "JSON"
}

// R8 minification infrastructure for benchmarking optimized builds
val r8Configuration: Configuration by configurations.creating

dependencies { r8Configuration("com.android.tools:r8:8.13.17") }

abstract class BaseR8Task : JavaExec() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val componentJarProp: RegularFileProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val runtimeClasspathProp: ConfigurableFileCollection

  fun r8ArgumentProvider(): CommandLineArgumentProvider {
    return CommandLineArgumentProvider {
      buildList {
        addAll(computeArgs())
        runtimeClasspathProp.files.filter { it.isFile }.forEach { file -> add(file.absolutePath) }
        add(componentJarProp.get().asFile.absolutePath)
      }
    }
  }

  abstract fun computeArgs(): Iterable<String>

  fun configureR8Inputs(
    componentJar: Provider<RegularFile>,
    runtimeClasspath: FileCollection,
  ) {
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
    }
  }
}

// Create custom R8 rules file that keeps the createAndInitialize function
val customR8RulesFile = layout.projectDirectory.file("proguard-rules.pro")

// Create a jar task for the component's compiled classes since it uses application plugin
val componentProject = project(":app:component")
val componentJar =
  tasks.register<Jar>("componentJar") {
    group = BUILD_GROUP
    description = "Creates a jar from :app:component compiled classes."

    // Depend on the component's compilation
    dependsOn(componentProject.tasks.named("classes"))

    // Include the compiled classes from the component project
    from(componentProject.layout.buildDirectory.dir("classes/kotlin/main"))
    from(componentProject.layout.buildDirectory.dir("classes/java/main"))

    archiveBaseName.set("component")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
  }

// Use the jmh configuration to get runtime classpath, but exclude the component project
// since we're providing our own jar with the component classes
val jmhRuntimeClasspath = configurations.named("jmh")
val componentBuildPath = componentProject.layout.buildDirectory.get().asFile.absolutePath
val filteredClasspath =
  jmhRuntimeClasspath.get().filter { file ->
    // Exclude anything from the component project's build directory to avoid duplicate classes
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
    configureR8Inputs(componentJar.flatMap { it.archiveFile }, filteredClasspath)
    argumentProviders += r8ArgumentProvider()
  }

val r8Task =
  tasks.register<R8Task>("componentJarR8") {
    group = BUILD_GROUP
    description = "Minifies the :app:component jar with R8 for optimized benchmark testing."

    inputs.files(r8Configuration)

    classpath(r8Configuration)
    mainClass.set("com.android.tools.r8.R8")

    javaHome.set(providers.systemProperty("java.home"))
    r8Rules.set(r8RulesExtractTask.flatMap { it.r8Rules })
    customRules.set(customR8RulesFile)
    r8Jar.set(layout.buildDirectory.file("libs/${project.name}-r8.jar"))
    mapping.set(layout.buildDirectory.file("libs/${project.name}-mapping.txt"))
    configureR8Inputs(componentJar.flatMap { it.archiveFile }, filteredClasspath)
    argumentProviders += r8ArgumentProvider()

    doLast {
      // Quick work around for https://issuetracker.google.com/issues/134372167.
      FileSystems.newFileSystem(r8Jar.get().asFile.toPath(), null as ClassLoader?).use { fs ->
        val root = fs.rootDirectories.first()
        listOf("module-info.class", "META-INF/versions/9/module-info.class").forEach { path ->
          val file = root.resolve(path)
          file.deleteIfExists()
        }
      }
    }
  }

// Create a JMH fat jar that uses the R8-minified component
val jmhJarR8 =
  tasks.register<Jar>("jmhJarR8") {
    group = BUILD_GROUP
    description = "Creates a JMH benchmark jar using R8-minified component."

    dependsOn(r8Task)
    dependsOn(tasks.named("jmhCompileGeneratedClasses"))
    dependsOn(tasks.named("jmhRunBytecodeGenerator"))

    archiveBaseName.set("${project.name}-jmh-r8")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))

    // Include the R8-minified component jar contents
    from(r8Task.map { zipTree(it.r8Jar) })

    // Include the JMH benchmark classes
    from(layout.buildDirectory.dir("jmh-generated-classes"))

    // Include the JMH generated resources (BenchmarkList, etc.)
    from(layout.buildDirectory.dir("jmh-generated-resources"))

    // Include the compiled JMH source classes
    from(layout.buildDirectory.dir("classes/kotlin/jmh"))
    from(layout.buildDirectory.dir("classes/java/jmh"))

    // Set the main class for JMH
    manifest { attributes["Main-Class"] = "org.openjdk.jmh.Main" }

    // Exclude duplicates (prefer first occurrence)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  }

// Create a custom JMH task that uses the R8-minified jar
tasks.register<JavaExec>("jmhR8") {
  group = VERIFICATION_GROUP
  description = "Runs JMH benchmarks with R8-minified classes."

  dependsOn(jmhJarR8)

  // Configure the JMH runtime
  mainClass.set("org.openjdk.jmh.Main")
  classpath =
    files(jmhJarR8.flatMap { it.archiveFile }) + configurations.named("jmhRuntimeClasspath").get()

  // Pass JMH arguments
  val jmhExtension = project.extensions.getByType<me.champeau.jmh.JmhParameters>()
  args = buildList {
    add("-wi")
    add(jmhExtension.warmupIterations.get().toString())
    add("-i")
    add(jmhExtension.iterations.get().toString())
    add("-f")
    add(jmhExtension.fork.get().toString())
    add("-rf")
    add(jmhExtension.resultFormat.get())
    add("-rff")
    add(layout.buildDirectory.file("results/jmhR8/results.json").get().asFile.absolutePath)
  }

  // Create output directory
  doFirst { layout.buildDirectory.dir("results/jmhR8").get().asFile.mkdirs() }
}
