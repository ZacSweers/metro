// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.graph.identity.TypeIdentity
import junit.framework.TestCase
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Key equality and ordering come from the key identity. Display text doesn't affect them. */
class KaTypeKeyIdentityTest : TestCase() {
  fun testDisplayTextDoesNotAffectEquality() {
    val expanded = KaTypeKey(snapshot("test.Payload", "test/Payload"))
    val alias = KaTypeKey(snapshot("test.PayloadAlias", "test/Payload"))

    assertEquals(expanded, alias)
    assertEquals(expanded.hashCode(), alias.hashCode())
    assertEquals(0, expanded.compareTo(alias))
  }

  fun testKeysThatRenderAlikeStayDistinct() {
    val first = KaTypeKey(snapshot("test.Payload", "first/Payload"))
    val second = KaTypeKey(snapshot("test.Payload", "second/Payload"))

    assertFalse(first == second)
    assertTrue(first.compareTo(second) != 0)
  }

  fun testQualifiersWithTheSameSimpleNameStayDistinct() {
    val type = snapshot("kotlin.String", "kotlin/String")
    val first = KaTypeKey(type, named("first"))
    val second = KaTypeKey(type, named("second"))

    assertEquals(first.render(short = true), second.render(short = true))
    assertFalse(first == second)
    assertTrue(first.compareTo(second) != 0)
  }

  fun testAbsentArgumentsKeepTheirPositions() {
    val classId = ClassId.topLevel(FqName("test.Key"))
    val first = Name.identifier("first")
    val second = Name.identifier("second")
    val value = KaAnnotationValueSnapshot.Literal("x")
    val firstOnly =
      KaAnnotationSnapshot(
        classId,
        listOf(first to value, second to KaAnnotationValueSnapshot.Absent),
      )
    val secondOnly =
      KaAnnotationSnapshot(
        classId,
        listOf(first to KaAnnotationValueSnapshot.Absent, second to value),
      )

    assertFalse(firstOnly == secondOnly)
  }

  private fun snapshot(rendered: String, classId: String) =
    KaTypeSnapshot(rendered, classId = null, identity = TypeIdentity.Class(classId))

  private fun named(packageName: String) =
    KaAnnotationSnapshot(ClassId.topLevel(FqName("$packageName.Named")), emptyList())
}
