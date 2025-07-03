// Repro https://github.com/ZacSweers/metro/issues/659
class ClassWithoutMembersInjector

class NoOpMembersInjector<T : Any> : MembersInjector<T> {
  override fun injectMembers(instance: T) {
  }
}

@DependencyGraph(AppScope::class)
interface TestGraph {

  val injector: MembersInjector<*>

  @Provides
  fun provideNoOpMembersInjector(
    instance: MembersInjector<ClassWithoutMembersInjector> = NoOpMembersInjector<ClassWithoutMembersInjector>()
  ): MembersInjector<*> {
    return instance
  }
}

fun box(): String {
  val graph = createGraph<TestGraph>()
  assertTrue(graph.injector is NoOpMembersInjector<*>)
  return "OK"
}