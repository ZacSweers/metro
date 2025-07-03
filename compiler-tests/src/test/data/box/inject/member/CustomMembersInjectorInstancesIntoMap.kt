// https://github.com/ZacSweers/metro/issues/659
import kotlin.reflect.KClass

class ClassWithoutMembersInjector

class NoOpMembersInjector<T : Any> : MembersInjector<T> {
  override fun injectMembers(instance: T) {
  }
}

@ContributesTo(AppScope::class)
interface Contribution {

  @Provides
  @IntoMap
  @ClassKey(ClassWithoutMembersInjector::class)
  fun provideClassWithoutMembersInjector(
    instance: MembersInjector<ClassWithoutMembersInjector> = NoOpMembersInjector<ClassWithoutMembersInjector>()
  ): MembersInjector<*> {
    return instance
  }
}

@DependencyGraph(AppScope::class)
interface TestGraph {

  @Multibinds
  val membersInjectors: Map<KClass<*>, MembersInjector<*>>
}

fun box(): String {
  val graph = createGraph<TestGraph>()
  assertTrue(graph.membersInjectors.size == 1)
  return "OK"
}