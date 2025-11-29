// https://github.com/ZacSweers/metro/issues/649
import kotlin.reflect.KClass

@Inject class Foo

@Inject class Bar

@HasMemberInjections
open class Parent {
  @Inject lateinit var foo: Foo
}

class Child : Parent() {
  @Inject lateinit var bar: Bar
}

@ContributesTo(AppScope::class)
interface MultibindingModule {

  @Binds
  @IntoMap
  @ClassKey(Parent::class)
  fun bindParent(instance: MembersInjector<Parent>): MembersInjector<*>

  @Binds
  @IntoMap
  @ClassKey(Child::class)
  fun bindChild(instance: MembersInjector<Child>): MembersInjector<*>
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Multibinds val membersInjectors: Map<KClass<*>, MembersInjector<*>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(graph.membersInjectors.isNotEmpty())
  return "OK"
}
