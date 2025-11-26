// https://github.com/ZacSweers/metro/pull/1313

// MODULE: lib
abstract class Parent {
  abstract val message: String
}

class MyClass : Parent() {
  @Inject override lateinit var message: String
  @Inject lateinit var ints: Set<Int>
}

// MODULE: main(lib)
@DependencyGraph
interface AppGraph {
  val myClassInjector: MembersInjector<MyClass>

  @Provides fun provideMessage(): String = "message"

  @Provides @IntoSet fun provideInt(): Int = 3
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val injector = graph.myClassInjector
  val myClass = MyClass()
  injector.injectMembers(myClass)
  assertEquals("message", myClass.message)
  assertEquals(setOf(3), myClass.ints)
  return "OK"
}
