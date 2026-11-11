// RENDER_DIAGNOSTICS_FULL_TEXT

// A graph only runs the callbacks it inherits, so these never run.
@BindingContainer
object ContainerObject {
  @OnGraphInit fun <!DEPENDENCY_GRAPH_ERROR!>start<!>() {}
}

@BindingContainer
interface ContainerInterface {
  @OnGraphClose fun <!DEPENDENCY_GRAPH_ERROR!>stop<!>() {}
}

@Inject class Service {
  @OnGraphInit fun <!DEPENDENCY_GRAPH_ERROR!>start<!>() {}
}

object Singleton {
  @OnGraphClose fun <!DEPENDENCY_GRAPH_ERROR!>stop<!>() {}
}

interface WithCompanion {
  companion object {
    @OnGraphInit fun <!DEPENDENCY_GRAPH_ERROR!>start<!>() {}
  }
}

// A graph can extend any of these.
interface Hooks {
  @OnGraphInit fun start() {}
}

abstract class AbstractHooks {
  @OnGraphInit fun start() {}
}

open class OpenHooks {
  @OnGraphClose fun stop() {}
}
