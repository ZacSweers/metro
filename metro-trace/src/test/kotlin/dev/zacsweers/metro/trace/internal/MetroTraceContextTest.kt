// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.trace.internal

import androidx.tracing.Tracer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class MetroTraceContextTest {
  @Test
  fun childReusesTracerAndExtendsGraphPath() {
    val tracer = Tracer.getStubTracer()
    val parent =
      MetroTraceContext(
        tracer = tracer,
        category = "dev.zacsweers.metro",
        graphName = "AppGraph",
        graphPath = "AppGraph",
      )

    val child = parent.child("ChildGraph")

    assertSame(tracer, child.tracer)
    assertEquals("dev.zacsweers.metro", child.category)
    assertEquals("ChildGraph", child.graphName)
    assertEquals("AppGraph/ChildGraph", child.graphPath)
    assertEquals("AppGraph", parent.graphPath)
  }

  @Test
  fun tracedProviderDelegatesToWrappedProvider() {
    val provider = TracedProvider(traceContext(), null, "String", "Provided") { "value" }

    assertEquals("value", provider())
  }

  @Test
  fun tracedProviderPropagatesDelegateExceptions() {
    val exception = IllegalStateException("boom")
    val provider =
      TracedProvider<String>(
        traceContext = traceContext(),
        qualifier = null,
        type = "String",
        kind = "Provided",
        provider = { throw exception },
      )

    assertSame(exception, assertFailsWith<IllegalStateException> { provider() })
  }

  @Test
  fun tracedMembersInjectorDelegatesToWrappedInjector() {
    val target = Target()
    val injector =
      TracedMembersInjector<Target>(
        traceContext = traceContext(),
        qualifier = null,
        type = "Target",
        injector = { it.value = "injected" },
      )

    injector.injectMembers(target)

    assertEquals("injected", target.value)
  }

  @Test
  fun tracedMembersInjectorPropagatesDelegateExceptions() {
    val exception = IllegalStateException("boom")
    val injector =
      TracedMembersInjector<Target>(
        traceContext = traceContext(),
        qualifier = null,
        type = "Target",
        injector = { throw exception },
      )

    assertSame(
      exception,
      assertFailsWith<IllegalStateException> { injector.injectMembers(Target()) },
    )
  }

  private fun traceContext(): MetroTraceContext {
    return MetroTraceContext(
      tracer = Tracer.getStubTracer(),
      category = "dev.zacsweers.metro",
      graphName = "AppGraph",
      graphPath = "AppGraph",
    )
  }

  private class Target {
    var value: String? = null
  }
}
