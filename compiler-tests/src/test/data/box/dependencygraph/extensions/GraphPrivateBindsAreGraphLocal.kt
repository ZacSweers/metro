@Inject class Registry

@Inject class Consumer(val registry: Registry)

@DependencyGraph(AppScope::class)
interface ParentGraph {
  @Provides @SingleIn(AppScope::class) @Named("real") fun real(): Registry = Registry()

  @Binds @GraphPrivate fun plain(@Named("real") real: Registry): Registry

  @Named("real") val realRegistry: Registry

  val consumer: Consumer

  fun createChild(): ChildGraph
}

@GraphExtension
interface ChildGraph {
  val registry: Registry

  fun createGrandchild(): GrandchildGraph
}

@GraphExtension
interface GrandchildGraph {
  val registry: Registry
}

fun box(): String {
  val parent = createGraph<ParentGraph>()
  assertSame(parent.realRegistry, parent.consumer.registry)

  val child = parent.createChild()
  assertNotSame(parent.realRegistry, child.registry)
  assertNotSame(parent.realRegistry, child.createGrandchild().registry)

  return "OK"
}
