// https://github.com/ZacSweers/metro/issues/2639

typealias AppScopeAlias = AppScope

interface Dependency

@ContributesTo(AppScopeAlias::class)
interface MyBindings {
  @Provides fun provideDependency(): Dependency = object : Dependency {}
}

@DependencyGraph(AppScope::class)
interface MyDependencyGraph {
  val dependency: Dependency
}

fun box(): String {
  assertNotNull(createGraph<MyDependencyGraph>().dependency)
  return "OK"
}
