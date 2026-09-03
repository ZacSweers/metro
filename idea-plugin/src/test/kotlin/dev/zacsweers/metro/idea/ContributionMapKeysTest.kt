// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.intentions.contributions.ContributionMapKeyChoice
import dev.zacsweers.metro.idea.intentions.contributions.contributionMapKeyChoices
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.name.ClassId
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

  fun testBuiltInKeysComeFirstAndRespectDefaults() {
    val choices = choices("class Implementation")
    assertEquals(
      listOf(
        "@dev.zacsweers.metro.ClassKey",
        "@dev.zacsweers.metro.StringKey(value = \"key\")",
        "@dev.zacsweers.metro.IntKey(value = 0)",
      ),
      choices.take(3).map { it.annotationText },
    )
    assertEmpty(choices.first().editableArguments)
    for (choice in choices.drop(1).take(2)) {
      assertEquals(listOf("value"), choice.editableArguments)
    }
  }

  fun testLegacyClassKeyKeepsItsRequiredArgument() {
    // Older runtimes require an explicit value and have no implicit-key default contract.
    myFixture.addFileToProject(
      "dev/zacsweers/metro/ClassKey.kt",
      """
      package dev.zacsweers.metro
      @MapKey
      @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
      annotation class ClassKey(val value: kotlin.reflect.KClass<*>)
      """
        .trimIndent(),
    )
    val key = choices("class Implementation").first()
    assertEquals(ClassId.fromString("dev/zacsweers/metro/ClassKey"), key.classId)
    assertEquals(
      "@dev.zacsweers.metro.ClassKey(value = test.Implementation::class)",
      key.annotationText,
    )
    assertEquals(listOf("value"), key.editableArguments)
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

  fun testModernClassKeyUsesItsImplicitDefaultAndIsFirst() {
    module.withMetroLibFixtureLibrary {
      project.setMetroOptions("custom-map-key" to "libtest/LibMapKeyContract")
      myFixture.addFileToProject(
        "dev/zacsweers/metro/ClassKey.kt",
        """
        package dev.zacsweers.metro
        @libtest.LibMapKeyContract(implicitClassKey = true)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
        annotation class ClassKey(val value: kotlin.reflect.KClass<*> = Nothing::class)
        """
          .trimIndent(),
      )
      val key = choices("class Implementation").first()
      assertEquals(ClassId.fromString("dev/zacsweers/metro/ClassKey"), key.classId)
      assertEquals("@dev.zacsweers.metro.ClassKey", key.annotationText)
      assertEmpty(key.editableArguments)
    }
  }

  fun testSourceImplicitCustomKeyUsesItsDefault() {
    module.withMetroLibFixtureLibrary {
      project.setMetroOptions("custom-map-key" to "libtest/LibMapKeyContract")
      val key =
        choices(
            """
        @libtest.LibMapKeyContract(implicitClassKey = true)
        annotation class ImplicitKey(val value: kotlin.reflect.KClass<*> = Nothing::class)
        class Implementation
        """
          )
          .single { it.classId == ClassId.fromString("test/ImplicitKey") }
      assertEquals("@test.ImplicitKey", key.annotationText)
      assertEmpty(key.editableArguments)
    }
  }

  fun testBinaryImplicitCustomKeyUsesMetadataContract() {
    module.withMetroLibFixtureLibrary {
      project.setMetroOptions("custom-map-key" to "libtest/LibMapKeyContract")
      val key =
        choices("class Implementation").single {
          it.classId == ClassId.fromString("libtest/LibImplicitClassKey")
        }
      assertEquals("@libtest.LibImplicitClassKey", key.annotationText)
      assertEmpty(key.editableArguments)
    }
  }

  fun testInvalidSourceImplicitDefaultsAreOmitted() {
    module.withMetroLibFixtureLibrary {
      project.setMetroOptions("custom-map-key" to "libtest/LibMapKeyContract")
      val keys =
        choices(
          """
        @libtest.LibMapKeyContract(implicitClassKey = true)
        annotation class WrongDefault(val value: kotlin.reflect.KClass<*> = String::class)
        @libtest.LibMapKeyContract(implicitClassKey = true)
        annotation class MissingDefault(val value: kotlin.reflect.KClass<*>)
        class Implementation
        """
        )
      assertFalse(keys.any { it.classId.packageFqName.asString() == "test" })
    }
  }

  fun testNestedMapKeysHaveDistinctLabels() {
    val keys =
      choices(
        """
      object First { @MapKey annotation class Key(val value: String) }
      object Second { @MapKey annotation class Key(val value: String) }
      class Implementation
      """
      )
    assertContainsElements(keys.map { it.label }, "@First.Key (test)", "@Second.Key (test)")
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
      listOf(
        ContributionMapKeyChoice(
          ClassId.fromString("test/EntryKey"),
          "Use existing @EntryKey",
          "",
          emptyList(),
        )
      ),
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
