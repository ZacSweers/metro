// MODULE: library

package feature

class FeatureScope
class Events {
  var starts = 0
}

interface Base {
  @OnGraphInit fun start(events: Events) { error("overridden") }
}

interface Left : Base
interface Right : Base

@ContributesTo(FeatureScope::class)
interface Hooks : Left, Right {
  @OnGraphInit override fun start(events: Events) { events.starts++ }
}

// MODULE: main(library)

import feature.*

private var calls = 0

@DependencyGraph(FeatureScope::class)
interface AppGraph : CloseableGraph {
  @OnGraphInit fun recordSecond(events: Events) { events.starts += 2 }
  @OnGraphInit fun record() { calls++ }

  @DependencyGraph.Factory fun interface Factory {
    fun create(@Provides events: Events): AppGraph
  }
}

fun box(): String {
  val events = Events()
  createGraphFactory<AppGraph.Factory>().create(events)
  check(events.starts == 3)
  check(calls == 1)
  return "OK"
}
