// ENABLE_SUSPEND_PROVIDERS

// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.SuspendProvider
import dev.zacsweers.metro.trace.internal.testMetroTrace
import kotlinx.coroutines.runBlocking

abstract class AppScope private constructor()

// Transitively suspend: unwrapped suspend dep. Multi-use so it gets a nested SuspendFactory +
// SuspendProvider field.
@Inject class Database(val url: String)

@Inject class Repository(val database: Database)

@DependencyGraph(scope = AppScope::class)
interface AppGraph {
  suspend fun repository(): Repository

  val databaseProvider: SuspendProvider<Database>

  @Provides @SingleIn(AppScope::class) suspend fun provideUrl(): String = "db://localhost"

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<AppGraph.Factory>().create(tracer)
    runBlocking {
      // Suspend accessor inlines Repository construction; Database resolves through its traced
      // SuspendProvider field; the scoped suspend String is computed once under its own span.
      assertEquals("db://localhost", graph.repository().database.url)
    }
    assertInstant(
      name = "AppGraph.repository",
      graph = "AppGraph",
      path = "AppGraph",
      callable = "repository",
      type = "Repository",
      kind = "Accessor",
    )
    assertTrace(
      name = "Repository",
      graph = "AppGraph",
      path = "AppGraph",
      type = "Repository",
      kind = "ConstructorInjected",
    )
    assertTrace(
      name = "Database",
      graph = "AppGraph",
      path = "AppGraph",
      type = "Database",
      kind = "ConstructorInjected",
    )
    assertTrace(
      name = "String",
      graph = "AppGraph",
      path = "AppGraph",
      type = "String",
      kind = "Provided",
    )

    runBlocking {
      // Second resolution: Database recomputes (unscoped) under its span; the scoped String is
      // cached by SuspendDoubleCheck, so no new String span.
      assertEquals("db://localhost", graph.databaseProvider().url)
    }
    assertInstant(
      name = "AppGraph.databaseProvider",
      graph = "AppGraph",
      path = "AppGraph",
      callable = "databaseProvider",
      type = "Database",
      contextualType = "SuspendProvider<Database>",
      kind = "Accessor",
    )
    assertTrace(
      name = "Database",
      graph = "AppGraph",
      path = "AppGraph",
      type = "Database",
      kind = "ConstructorInjected",
    )
  }
  return "OK"
}
