// https://github.com/ZacSweers/metro/issues/896

// MODULE: lib
@HasMemberInjections
open class Parent {
  @Inject lateinit var foo: Foo
}

@Inject class Foo

// MODULE: main(lib)
import kotlin.reflect.KClass

class Child : Parent()

class ChildWithAttribute : Parent() {
  @Inject lateinit var foo2: Foo
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

  @Binds
  @IntoMap
  @ClassKey(ChildWithAttribute::class)
  fun bindChildWithAttribute(instance: MembersInjector<ChildWithAttribute>): MembersInjector<*>
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Multibinds val membersInjectors: Map<KClass<*>, MembersInjector<*>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(3, graph.membersInjectors.size)
  return "OK"
}
