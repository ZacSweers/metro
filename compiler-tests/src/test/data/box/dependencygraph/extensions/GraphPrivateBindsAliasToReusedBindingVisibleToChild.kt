// A child reads a published @Binds alias from its parent. A private @Binds chain ends that alias at
// an unscoped binding that the parent reuses from its own parent.
interface Api

interface Mid : Api

@Inject class Dep

@Inject class Impl(val dep: Dep) : Mid

@DependencyGraph
interface AppGraph {
  val impl: Impl
  val middleFactory: Middle.Factory
}

@GraphExtension
interface Middle {
  val impl: Impl
  val childFactory: Child.Factory

  @GraphPrivate @Binds val Impl.bindMid: Mid

  @Binds val Mid.bindApi: Api

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): Middle
  }
}

@GraphExtension
interface Child {
  val api: Api
  val apiProvider: Provider<Api>

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): Child
  }
}

fun box(): String {
  val child = createGraph<AppGraph>().middleFactory.create().childFactory.create()
  val first = child.api
  assertTrue(first is Impl)
  // Impl is unscoped, so each read builds a new instance.
  assertNotSame(first, child.api)
  val provider = child.apiProvider
  assertNotSame(provider(), provider())
  return "OK"
}
