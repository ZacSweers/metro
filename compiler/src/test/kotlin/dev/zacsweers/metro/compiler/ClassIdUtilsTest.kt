// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import kotlin.test.Test
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class ClassIdUtilsTest {
  @Test
  fun `fitting generated names stay unchanged`() {
    val preferredName = "ProvideRepositoryAsStoreMetroFactory"

    val actual =
      truncatedName(
        parentClassId = classId("RepositoryContributions.ToAppScope"),
        preferredName = preferredName,
        identity = "example.Repository.provideStore",
        requiredSuffix = "MetroFactory",
        reservedNestedBytes = COMPANION_SUFFIX.length,
        maxBytes = DEFAULT_MAX_GENERATED_CLASS_NAME_LENGTH,
      )

    assertThat(actual.asString()).isEqualTo(preferredName)
  }

  @Test
  fun `the complete binary basename includes parents and the companion`() {
    val parent = classId("Outer.Inner")
    val preferredName = "Provide" + "Descriptive".repeat(30) + "MetroFactory"
    val actual =
      truncatedName(
          parentClassId = parent,
          preferredName = preferredName,
          identity = "example.Outer.Inner.provideValue",
          requiredSuffix = "MetroFactory",
          reservedNestedBytes = COMPANION_SUFFIX.length,
          maxBytes = 150,
        )
        .asString()

    assertThat(actual).endsWith("MetroFactory")
    assertThat(actual).isNotEqualTo(preferredName)
    assertThat(binaryName(parent, actual, COMPANION_SUFFIX).encodeToByteArray().size).isEqualTo(150)
  }

  @Test
  fun `an exact fit is unchanged and one extra byte is shortened`() {
    val parent = classId("Outer.Inner")
    val availableBytes =
      150 - binaryName(parent).encodeToByteArray().size - 1 - COMPANION_SUFFIX.length
    val exactName = "A".repeat(availableBytes - "MetroFactory".length) + "MetroFactory"

    fun name(preferredName: String): String =
      truncatedName(
          parentClassId = parent,
          preferredName = preferredName,
          identity = preferredName,
          requiredSuffix = "MetroFactory",
          reservedNestedBytes = COMPANION_SUFFIX.length,
          maxBytes = 150,
        )
        .asString()

    assertThat(name(exactName)).isEqualTo(exactName)
    val shortened = name("A$exactName")
    assertThat(shortened).isNotEqualTo("A$exactName")
    assertThat(binaryName(parent, shortened, COMPANION_SUFFIX).encodeToByteArray().size)
      .isEqualTo(150)
  }

  @Test
  fun `shortened names are stable and retain the complete identity`() {
    val preferredName = "Provide" + "SamePrefix".repeat(30) + "MetroFactory"
    fun name(identity: String): String =
      truncatedName(
          parentClassId = classId("Outer.Inner"),
          preferredName = preferredName,
          identity = identity,
          requiredSuffix = "MetroFactory",
          reservedNestedBytes = COMPANION_SUFFIX.length,
          maxBytes = 150,
        )
        .asString()

    val first = name("example.Impl|scope.One|binding|example.Bound|qualifier=one|mapKey=one")
    assertThat(first)
      .isEqualTo(name("example.Impl|scope.One|binding|example.Bound|qualifier=one|mapKey=one"))
    assertThat(first)
      .isNotEqualTo(name("example.Impl|scope.Two|binding|example.Bound|qualifier=one|mapKey=one"))
    assertThat(first)
      .isNotEqualTo(name("example.Impl|scope.One|map|example.Bound|qualifier=one|mapKey=two"))
    assertThat(first).endsWith("MetroFactory")
  }

  @Test
  fun `a short canonical fallback can require an identity hash`() {
    fun name(identity: String): String =
      truncatedName(
          parentClassId = null,
          preferredName = "ProvideValueMetroFactory",
          identity = identity,
          requiredSuffix = "MetroFactory",
          maxBytes = 150,
          forceHash = true,
        )
        .asString()

    val first = name("first-callable")
    assertThat(first).isNotEqualTo("ProvideValueMetroFactory")
    assertThat(first).isEqualTo(name("first-callable"))
    assertThat(first).isNotEqualTo(name("second-callable"))
    assertThat(first).endsWith("MetroFactory")
  }

  @Test
  fun `provider factory IDs use the same complete name budget`() {
    val parent = classId("Outer.Inner")
    val callable = Name.identifier("provide" + "Descriptive".repeat(30))

    val factory = providerFactoryClassId(parent, callable, maxBytes = 150)

    assertThat(factory.outerClassId).isEqualTo(parent)
    assertThat(factory.shortClassName.asString()).endsWith("MetroFactory")
    assertThat((factory.binaryClassName() + COMPANION_SUFFIX).encodeToByteArray().size)
      .isEqualTo(150)
    assertThat(providerFactoryClassId(parent, callable, maxBytes = 150)).isEqualTo(factory)
  }

  @Test
  fun `the minimum budget reserves the complete contribution hierarchy`() {
    val limit = 96
    val source = classId("Outer" + "Source".repeat(35) + ".Inner" + "Source".repeat(35))
    val scope = classId("Scope".repeat(40) + "One")
    val callable = Name.identifier("provide" + "Value".repeat(40))

    val holder = MetroContributions.holderClassId(source, limit)
    val container = MetroContributions.containerObjectClassId(source, scope, limit)
    val factory = providerFactoryClassId(container, callable, limit)

    assertThat(container.outerClassId).isEqualTo(holder)
    assertThat(factory.outerClassId).isEqualTo(container)
    assertThat(holder.binaryClassName().encodeToByteArray().size).isAtMost(limit)
    assertThat(container.binaryClassName().encodeToByteArray().size).isAtMost(limit)
    assertThat((factory.binaryClassName() + COMPANION_SUFFIX).encodeToByteArray().size)
      .isAtMost(limit)
    assertThat(providerFactoryClassId(container, callable, limit)).isEqualTo(factory)

    val otherSource = classId(source.relativeClassName.asString() + "Other")
    val otherScope = classId("Scope".repeat(40) + "Two")
    assertThat(MetroContributions.holderClassId(otherSource, limit)).isNotEqualTo(holder)
    assertThat(MetroContributions.containerObjectClassId(source, otherScope, limit))
      .isNotEqualTo(container)
    assertThat(
        providerFactoryClassId(container, Name.identifier(callable.asString() + "Other"), limit)
      )
      .isNotEqualTo(factory)
  }

  @Test
  fun `UTF-8 limits do not split supplementary characters`() {
    val parent = classId("外部.内部")
    val preferredName = "提供" + "名前🚀".repeat(40) + "MetroFactory"
    val actual =
      truncatedName(
          parentClassId = parent,
          preferredName = preferredName,
          identity = "example.提供.名前🚀",
          requiredSuffix = "MetroFactory",
          reservedNestedBytes = COMPANION_SUFFIX.length,
          maxBytes = 150,
        )
        .asString()

    // Strict encoding rejects an unpaired surrogate left by character-count truncation.
    val encoded =
      binaryName(parent, actual, COMPANION_SUFFIX).encodeToByteArray(throwOnInvalidSequence = true)
    assertThat(encoded.size).isAtMost(150)
    assertThat(encoded.decodeToString(throwOnInvalidSequence = true))
      .isEqualTo(binaryName(parent, actual, COMPANION_SUFFIX))
    assertThat(actual).endsWith("MetroFactory")
  }

  @Test
  fun `the package path does not consume the basename budget`() {
    val preferredName = "Provide" + "Descriptive".repeat(30) + "MetroFactory"
    fun name(packageName: String): String =
      truncatedName(
          parentClassId = ClassId(FqName(packageName), FqName("Outer.Inner"), false),
          preferredName = preferredName,
          identity = "same-declaration",
          requiredSuffix = "MetroFactory",
          reservedNestedBytes = COMPANION_SUFFIX.length,
          maxBytes = 150,
        )
        .asString()

    assertThat(name("example")).isEqualTo(name("example." + "longpackage.".repeat(30) + "last"))
  }

  @Test
  fun `long authored containers use the existing compact hash`() {
    val parent =
      classId(
        "AuthoredBindingContainerWhoseNameLeavesNoRoomForTheRequiredGeneratedFactoryAnd" +
          "CompanionAtTheConfiguredNameLimit"
      )
    val factory =
      providerFactoryClassId(
        parent,
        Name.identifier("provideAValueWithDependencies"),
        maxBytes = 150,
      )

    assertThat(factory.outerClassId).isEqualTo(parent)
    assertThat(factory.shortClassName.asString()).endsWith("MetroFactory")
    assertThat((factory.binaryClassName() + COMPANION_SUFFIX).encodeToByteArray().size)
      .isAtMost(150)
  }

  private fun truncatedName(
    parentClassId: ClassId?,
    preferredName: String,
    identity: String,
    requiredSuffix: String = "",
    reservedNestedBytes: Int = 0,
    maxBytes: Int,
    forceHash: Boolean = false,
  ): Name {
    val name = Name.identifier(preferredName)
    val classId =
      if (parentClassId == null) ClassId(FqName("example"), name)
      else parentClassId.createNestedClassId(name)
    return classId
      .truncate(
        maxLength = maxBytes,
        reservedNestedBytes = reservedNestedBytes,
        hashSource = identity,
        requiredSuffix = requiredSuffix,
        forceHash = forceHash,
      )
      .shortClassName
  }

  private fun classId(relativeName: String): ClassId =
    ClassId(FqName("example"), FqName(relativeName), false)

  private fun binaryName(parent: ClassId, simpleName: String = "", suffix: String = ""): String {
    val parentName = parent.binaryClassName()
    if (simpleName.isEmpty()) return parentName
    return "$parentName\$$simpleName$suffix"
  }

  private companion object {
    const val COMPANION_SUFFIX = "\$Companion"
  }
}
