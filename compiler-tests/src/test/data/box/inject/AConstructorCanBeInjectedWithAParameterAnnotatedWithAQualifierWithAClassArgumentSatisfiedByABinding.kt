package test

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  // This is fine
  @ForScope(AppScope::class)
  val contributedInterface: ContributedInterface

  val myClass: MyClass
}

interface ContributedInterface

@ContributesBinding(AppScope::class, binding<@ForScope(AppScope::class) ContributedInterface>())
@Inject
class Impl : ContributedInterface

class MyClass @Inject constructor(
  // This fails
  @ForScope(AppScope::class)
  val contributedInterface: ContributedInterface
)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val contributedInterface = graph.contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(Impl::class, contributedInterface::class)
  val myClass = graph.myClass
  val myClassField = myClass.contributedInterface
  assertNotNull(myClass)
  assertNotNull(myClassField)
  assertEquals(Impl::class, myClassField::class)
  return "OK"
}
