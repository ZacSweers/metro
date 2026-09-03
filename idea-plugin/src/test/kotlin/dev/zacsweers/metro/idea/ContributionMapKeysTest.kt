// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.intentions.contributions.ContributionMapKeyChoice
import dev.zacsweers.metro.idea.intentions.contributions.contributionMapKeyChoices
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

/** Checks detached templates and the source visibility used by the explicit contribution picker. */
class ContributionMapKeysTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    module.addKotlinStdlibLibrary()
  }

  fun testBuiltInKeysHaveNamedEditableArguments() {
    val choices = choices("class Implementation")
    assertEquals(
      listOf(
        "@dev.zacsweers.metro.StringKey(value = \"key\")",
        "@dev.zacsweers.metro.ClassKey(value = test.Implementation::class)",
        "@dev.zacsweers.metro.IntKey(value = 0)",
      ),
      choices.take(3).map { it.annotationText },
    )
    for (choice in choices.take(3)) assertEquals(listOf("value"), choice.editableArguments)
  }

  fun testCustomRequiredArgumentsKeepDefaultsOmitted() {
    val choice =
      choices(
          """
      enum class Flavor { FIRST, SECOND }
      interface Api
      @MapKey(unwrapValue = false)
      @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
      annotation class EntryKey(
        val name: String,
        val count: Int,
        val flavor: Flavor,
        val type: kotlin.reflect.KClass<out Api>,
        val version: Long = 17L,
      )
      class Implementation : Api
      """
        )
        .single { it.annotationText.startsWith("@test.EntryKey") }
    assertEquals(
      "@test.EntryKey(name = \"key\", count = 0, flavor = test.Flavor.FIRST, type = test.Implementation::class)",
      choice.annotationText,
    )
    assertEquals(listOf("name", "count", "flavor", "type"), choice.editableArguments)
  }

  fun testGenericClassKeyUsesCompatibleImplementation() {
    val choice =
      choices(
          """
      interface Handler<T>
      @MapKey annotation class HandlerKey(val value: kotlin.reflect.KClass<out Handler<String>>)
      class Implementation : Handler<String>
      """
        )
        .single { it.annotationText.startsWith("@test.HandlerKey") }
    assertEquals("@test.HandlerKey(value = test.Implementation::class)", choice.annotationText)
    assertEquals(listOf("value"), choice.editableArguments)
  }

  fun testIncompatibleGenericClassKeyIsOmitted() {
    val choices =
      choices(
        """
      interface Handler<T>
      @MapKey annotation class HandlerKey(val value: kotlin.reflect.KClass<out Handler<String>>)
      class Implementation : Handler<Int>
      """
      )
    assertFalse(choices.any { it.annotationText.startsWith("@test.HandlerKey") })
  }

  fun testClassKeyCanUseStarProjectedBound() {
    val choice =
      choices(
          """
      interface Handler<T>
      @MapKey annotation class HandlerKey(val value: kotlin.reflect.KClass<out Handler<*>>)
      class Implementation
      """
        )
        .single { it.annotationText.startsWith("@test.HandlerKey") }
    assertEquals("@test.HandlerKey(value = test.Handler::class)", choice.annotationText)
  }

  fun testImportAndTypeAliasesFindKeysInOtherFiles() {
    myFixture.addFileToProject(
      "keys/Markers.kt",
      """
      package keys
      import dev.zacsweers.metro.MapKey as Marker
      typealias Alias = Marker
      @Marker annotation class ImportedKey(val value: String)
      """
        .trimIndent(),
    )
    myFixture.addFileToProject(
      "keys/AliasedKey.kt",
      """
      package keys
      @Alias annotation class AliasedKey(val value: Int)
      """
        .trimIndent(),
    )
    val annotations = choices("class Implementation").map { it.annotationText }
    assertContainsElements(
      annotations,
      "@keys.ImportedKey(value = \"key\")",
      "@keys.AliasedKey(value = 0)",
    )
  }

  fun testBinaryCustomKeyKeepsDefaultsOmitted() {
    module.withMetroLibFixtureLibrary {
      val choice =
        choices("class Implementation").single {
          it.annotationText.startsWith("@libtest.LibContributionMapKey")
        }
      assertEquals("@libtest.LibContributionMapKey(name = \"key\")", choice.annotationText)
      assertEquals(listOf("name"), choice.editableArguments)
    }
  }

  fun testExistingMapKeyIsReused() {
    val choices =
      choices(
        """
      @MapKey annotation class EntryKey(val value: String)
      @EntryKey("existing") class Implementation
      """
      )
    assertEquals(
      listOf(ContributionMapKeyChoice("Use existing @EntryKey", "", emptyList())),
      choices,
    )
  }

  fun testUnsupportedAndInvalidKeysAreOmitted() {
    val choices =
      choices(
        """
      annotation class Nested(val value: String)
      @MapKey annotation class EmptyKey
      @MapKey(unwrapValue = false) annotation class WrappedEmptyKey
      @MapKey annotation class MultipleKey(val first: String, val second: Int)
      @MapKey annotation class ArrayKey(val values: IntArray)
      @MapKey(unwrapValue = false) annotation class RequiredArrayKey(val values: IntArray)
      @MapKey annotation class NestedKey(val nested: Nested)
      @MapKey @Target(AnnotationTarget.CLASS) annotation class ClassOnlyKey(val value: String)
      @MapKey @Target(AnnotationTarget.FUNCTION) annotation class FunctionOnlyKey(val value: String)
      @MapKey(implicitClassKey = true) annotation class InvalidImplicitKey(val value: String = "wrong")
      class Implementation
      """
      )
    assertEquals(3, choices.size)
  }

  fun testPrivateKeysFromOtherFilesAreOmitted() {
    myFixture.addFileToProject(
      "keys/PrivateKey.kt",
      """
      package keys
      import dev.zacsweers.metro.MapKey
      @MapKey private annotation class PrivateKey(val value: String)
      """
        .trimIndent(),
    )
    assertFalse(choices("class Implementation").any { it.annotationText.contains("PrivateKey") })
  }

  fun testUnrelatedMapKeyAnnotationDoesNotQualify() {
    myFixture.addFileToProject(
      "fake/MapKey.kt",
      """
      package fake
      annotation class MapKey
      @MapKey annotation class FakeKey(val value: String)
      """
        .trimIndent(),
    )
    assertFalse(choices("class Implementation").any { it.annotationText.contains("FakeKey") })
  }

  private fun choices(declarations: String): List<ContributionMapKeyChoice> {
    val file =
      myFixture.configureByText(
        "Implementation.kt",
        """
      package test
      import dev.zacsweers.metro.MapKey
      $declarations
      """
          .trimIndent(),
      ) as KtFile
    val owner =
      file.declarations.filterIsInstance<KtClassOrObject>().single { it.name == "Implementation" }
    return allowAnalysisOnEdt {
      analyze(owner) { contributionMapKeyChoices(owner, owner.metroIdeState().options) }
    }
  }
}
