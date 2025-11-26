// ENABLE_DAGGER_INTEROP
package test

import javax.inject.Provider

@DependencyGraph(AppScope::class) interface AppGraph

interface Dependency

@dagger.Module
@ContributesTo(AppScope::class)
interface DependencyModule {
  companion object {
    @dagger.Provides
    @SingleIn(AppScope::class)
    fun provideDependency(): Dependency = object : Dependency {}
  }
}

@ContributesTo(AppScope::class)
interface DependencyProvider {
  val provider: Provider<Dependency>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.asContribution<DependencyProvider>().provider.get())
  return "OK"
}
