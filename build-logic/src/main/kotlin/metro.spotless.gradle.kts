// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessExtensionPredeclare
import com.diffplug.spotless.LineEnding

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
val ktfmtVersion = catalog.findVersion("ktfmt").get().requiredVersion
val gjfVersion = catalog.findVersion("gjf").get().requiredVersion

val spotlessDir =
  rootProject.file("spotless").takeIf(File::exists) ?: rootProject.file("../spotless")

apply(plugin = "com.diffplug.spotless")

val isRootProject = this == rootProject

if (isRootProject) {
  configure<SpotlessExtensionPredeclare> {
    kotlin { ktfmt(ktfmtVersion).googleStyle().configure { it.setRemoveUnusedImports(true) } }
    kotlinGradle { ktfmt(ktfmtVersion).googleStyle().configure { it.setRemoveUnusedImports(true) } }
    java {
      googleJavaFormat(gjfVersion).reorderImports(true).reflowLongStrings(true).reorderImports(true)
    }
  }
}

configure<SpotlessExtension> {
  if (isRootProject) {
    predeclareDeps()
  }
  setLineEndings(LineEnding.GIT_ATTRIBUTES_FAST_ALLSAME)
  format("misc") {
    target("*.gradle", "*.md", ".gitignore")
    trimTrailingWhitespace()
    leadingTabsToSpaces(2)
    endWithNewline()
  }
  java {
    googleJavaFormat(gjfVersion).reorderImports(true).reflowLongStrings(true).reorderImports(true)
    target("src/**/*.java")
    trimTrailingWhitespace()
    endWithNewline()
    targetExclude("**/spotless.java")
    targetExclude("**/src/test/data/**")
    targetExclude("**/*Generated.java")
  }
  kotlin {
    ktfmt(ktfmtVersion).googleStyle().configure { it.setRemoveUnusedImports(true) }
    target("src/**/*.kt")
    trimTrailingWhitespace()
    endWithNewline()
    targetExclude("**/spotless.kt")
    targetExclude("**/src/test/data/**")
  }
  kotlinGradle {
    ktfmt(ktfmtVersion).googleStyle().configure { it.setRemoveUnusedImports(true) }
    target("*.kts")
    trimTrailingWhitespace()
    endWithNewline()
    licenseHeaderFile(
      spotlessDir.resolve("spotless.kt"),
      "(@file:|import|plugins|buildscript|dependencies|pluginManagement|dependencyResolutionManagement)",
    )
  }
  format("licenseKotlin") {
    licenseHeaderFile(spotlessDir.resolve("spotless.kt"), "(package|@file:)")
    target("src/**/*.kt")
    targetExclude(
      "**/src/test/data/**",
      "**/AbstractMapFactory.kt",
      "**/Assisted.kt",
      "**/AssistedFactory.kt",
      "**/BaseDoubleCheck.kt",
      "**/ClassKey.kt",
      "**/DelegateFactory.kt",
      "**/DoubleCheck.kt",
      "**/DoubleCheckCycleTest.kt",
      "**/DoubleCheckTest.kt",
      "**/ElementsIntoSet.kt",
      "**/InstanceFactory.kt",
      "**/InstanceFactoryTest.kt",
      "**/IntKey.kt",
      "**/IntoMap.kt",
      "**/IntoSet.kt",
      "**/KotlinToolingVersion.kt",
      "**/LongKey.kt",
      "**/MapFactory.kt",
      "**/MapKey.kt",
      "**/MapLazyFactory.kt",
      "**/MapProviderFactory.kt",
      "**/MapProviderFactoryTest.kt",
      "**/MapProviderLazyFactory.kt",
      "**/MembersInjector.kt",
      "**/MemoizedSequence.kt",
      "**/Multibinds.kt",
      "**/NameAllocator.kt",
      "**/NameAllocatorTest.kt",
      "**/ProviderOfLazy.kt",
      "**/SetFactory.kt",
      "**/SetFactoryTest.kt",
      "**/StringKey.kt",
      "**/TopologicalSortTest.kt",
      "**/collectionUtil.kt",
      "**/ir/cache/*.kt",
      "**/topologicalSort.kt",
    )
  }
  format("licenseJava") {
    licenseHeaderFile(spotlessDir.resolve("spotless.java"), "package")
    target("src/**/*.java")
    targetExclude("**/BetweennessCentrality.java", "**/*Generated.java")
  }
}
