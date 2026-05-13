// PARALLEL_THREADS: 4

abstract class Feature1Scope

abstract class Feature2Scope

abstract class Feature3Scope

abstract class Feature4Scope

@Inject @SingleIn(AppScope::class) class SharedService(val id: String)

// 4 extensions all depending on the parent-scoped SharedService
@GraphExtension(Feature1Scope::class)
interface Feature1Graph {
  val shared: SharedService

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createFeature1(): Feature1Graph
  }
}

@GraphExtension(Feature2Scope::class)
interface Feature2Graph {
  val shared: SharedService

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createFeature2(): Feature2Graph
  }
}

@GraphExtension(Feature3Scope::class)
interface Feature3Graph {
  val shared: SharedService

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createFeature3(): Feature3Graph
  }
}

@GraphExtension(Feature4Scope::class)
interface Feature4Graph {
  val shared: SharedService

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createFeature4(): Feature4Graph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides fun provideId(): String = "shared-instance"
}

fun box(): String {
  val app = createGraph<AppGraph>()
  val f1 = app.createFeature1()
  val f2 = app.createFeature2()
  val f3 = app.createFeature3()
  val f4 = app.createFeature4()
  // All children should see the same parent-scoped instance
  assertEquals("shared-instance", f1.shared.id)
  assertEquals("shared-instance", f2.shared.id)
  assertEquals("shared-instance", f3.shared.id)
  assertEquals("shared-instance", f4.shared.id)
  return "OK"
}
