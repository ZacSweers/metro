/*
 * Copyright (C) 2024 Zac Sweers
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
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-gradle-plugin`
  alias(libs.plugins.dokka)
  alias(libs.plugins.mavenPublish)
  alias(libs.plugins.spotless)
  alias(libs.plugins.buildConfig)
}

java { toolchain { languageVersion.set(libs.versions.jdk.map(JavaLanguageVersion::of)) } }

tasks.withType<JavaCompile>().configureEach {
  options.release.set(libs.versions.jvmTarget.map(String::toInt))
}

buildConfig {
  packageName("dev.zacsweers.lattice.gradle")
  useKotlinOutput {
    topLevelConstants = true
    internalVisibility = true
  }
  buildConfigField("String", "VERSION", "\"${project.property("VERSION_NAME")}\"")
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(libs.versions.jvmTarget.map(JvmTarget::fromTarget))

    // Lower version for Gradle compat
    languageVersion.set(KotlinVersion.KOTLIN_1_9)
    apiVersion.set(KotlinVersion.KOTLIN_1_9)
  }
}

gradlePlugin {
  plugins {
    create("latticePlugin") {
      id = "dev.zacsweers.lattice"
      implementationClass = "dev.zacsweers.lattice.gradle.LatticeGradleSubplugin"
    }
  }
}

dokka {
  dokkaPublications.html {
    outputDirectory.set(rootDir.resolve("docs/api/0.x"))
    includes.from(project.layout.projectDirectory.file("README.md"))
  }
  basePublicationsDirectory.set(layout.buildDirectory.dir("dokkaDir"))
  dokkaSourceSets.configureEach {
    skipDeprecated.set(true)
    documentedVisibilities.add(VisibilityModifier.Public)
    externalDocumentationLinks.register("Gradle") {
      packageListUrl("https://docs.gradle.org/${gradle.gradleVersion}/javadoc/element-list")
      url("https://docs.gradle.org/${gradle.gradleVersion}/javadoc")
    }
    // KGP docs
    externalDocumentationLinks.register("KGP") {
      url("https://kotlinlang.org/api/kotlin-gradle-plugin/")
    }
    sourceLink {
      localDirectory.set(layout.projectDirectory.dir("src"))
      val relPath = rootProject.projectDir.toPath().relativize(projectDir.toPath())
      remoteUrl(
        providers.gradleProperty("POM_SCM_URL").map { scmUrl -> "$scmUrl/tree/main/$relPath/src" }
      )
      remoteLineSuffix.set("#L")
    }
  }
}

kotlin { explicitApi() }

spotless {
  format("misc") {
    target("*.gradle", "*.md", ".gitignore")
    trimTrailingWhitespace()
    indentWithSpaces(2)
    endWithNewline()
  }
  kotlin {
    target("src/**/*.kt")
    ktfmt(libs.versions.ktfmt.get())
    trimTrailingWhitespace()
    endWithNewline()
    licenseHeaderFile("../spotless/spotless.kt")
  }
}

dependencies {
  compileOnly(libs.kotlin.gradlePlugin)
  compileOnly(libs.kotlin.gradlePlugin.api)
  compileOnly(libs.kotlin.stdlib)
}

configure<MavenPublishBaseExtension> { publishToMavenCentral(automaticRelease = true) }

// configuration required to produce unique META-INF/*.kotlin_module file names
tasks.withType<KotlinCompile>().configureEach {
  compilerOptions { moduleName.set(project.property("POM_ARTIFACT_ID") as String) }
}
