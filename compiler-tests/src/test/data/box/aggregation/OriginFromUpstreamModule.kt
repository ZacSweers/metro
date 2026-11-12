// Regression test for https://github.com/ZacSweers/metro/issues/3098
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

// MODULE: upstream
// FILE: Upstream.kt
package test.upstream

interface Service {
  val value: String
}

class OriginTrigger

// MODULE: main(upstream)
// FILE: Downstream.kt
package test.downstream

import test.upstream.OriginTrigger
import test.upstream.Service

@Origin(OriginTrigger::class)
@ContributesBinding(AppScope::class)
@Inject
class RealService : Service {
  override val value = "real"
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("real", graph.service.value)
  return "OK"
}
