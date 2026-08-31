// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import java.util.TreeMap
import java.util.TreeSet
import kotlin.test.assertFailsWith
import org.junit.Test

class DiagnosticRoutesTest {
  @Test
  fun `route indexing checks for cancellation`() {
    val root = routeContextKey("Node0")
    val adjacency = TreeMap<StringTypeKey, Set<StringTypeKey>>()
    repeat(300) { index ->
      adjacency[routeKey("Node$index")] = TreeSet(setOf(routeKey("Node${index + 1}")))
    }
    adjacency[routeKey("Node300")] = TreeSet()
    val routes = DiagnosticRoutes(mapOf(root to StringBindingStack.Entry(root)), adjacency)
    var checks = 0

    assertFailsWith<RouteCancellationException> {
      routes.routeToRoot(
        key = routeKey("Node300"),
        ensureActive = {
          if (++checks == 2) throw RouteCancellationException()
        },
        createDependencyEntry = ::dependencyEntry,
      )
    }

    assertThat(checks).isEqualTo(2)
  }

  @Test
  fun `cached route reconstruction checks for cancellation`() {
    val root = routeContextKey("Root")
    val routes =
      DiagnosticRoutes(
        roots = mapOf(root to StringBindingStack.Entry(root)),
        adjacency =
          sortedAdjacency(
            "Root" to setOf("Middle"),
            "Middle" to setOf("Target"),
            "Target" to emptySet(),
          ),
      )
    routes.routeToRoot(routeKey("Target"), createDependencyEntry = ::dependencyEntry)

    assertFailsWith<RouteCancellationException> {
      routes.routeToRoot(
        key = routeKey("Target"),
        ensureActive = { throw RouteCancellationException() },
        createDependencyEntry = ::dependencyEntry,
      )
    }
  }

  @Test
  fun `uses the shortest deterministic route from a graph root`() {
    val firstRoot = routeContextKey("FirstRoot")
    val secondRoot = routeContextKey("SecondRoot")
    val roots =
      linkedMapOf(
        firstRoot to StringBindingStack.Entry(firstRoot, usage = "first"),
        secondRoot to StringBindingStack.Entry(secondRoot, usage = "second"),
      )
    val adjacency =
      sortedAdjacency(
        "FirstRoot" to setOf("Middle"),
        "Middle" to setOf("Target"),
        "SecondRoot" to setOf("Target"),
        "Target" to emptySet(),
      )
    val routes = DiagnosticRoutes(roots, adjacency)

    val route = routes.routeToRoot(routeKey("Target"), createDependencyEntry = ::dependencyEntry)

    assertThat(route.map { it.usage }).containsExactly("second", "SecondRoot -> Target").inOrder()
  }

  @Test
  fun `returns no route for an unreachable key`() {
    val root = routeContextKey("Root")
    val routes =
      DiagnosticRoutes(
        roots = mapOf(root to StringBindingStack.Entry(root)),
        adjacency = sortedAdjacency("Root" to emptySet(), "Unreachable" to emptySet()),
      )

    assertThat(
        routes.routeToRoot(routeKey("Unreachable"), createDependencyEntry = ::dependencyEntry)
      )
      .isEmpty()
  }
}

private class RouteCancellationException : RuntimeException()

private fun dependencyEntry(
  callingKey: StringTypeKey,
  dependencyKey: StringTypeKey,
): StringBindingStack.Entry =
  StringBindingStack.Entry(
    routeContextKey(dependencyKey.type),
    usage = "${callingKey.type} -> ${dependencyKey.type}",
  )

private fun sortedAdjacency(
  vararg entries: Pair<String, Set<String>>
): Map<StringTypeKey, Set<StringTypeKey>> =
  TreeMap<StringTypeKey, Set<StringTypeKey>>().apply {
    for ((name, dependencies) in entries) {
      put(routeKey(name), TreeSet(dependencies.map(::routeKey)))
    }
  }

private fun routeKey(type: String): StringTypeKey = StringTypeKey(type)

private fun routeContextKey(type: String): StringContextualTypeKey =
  StringContextualTypeKey.create(routeKey(type))
