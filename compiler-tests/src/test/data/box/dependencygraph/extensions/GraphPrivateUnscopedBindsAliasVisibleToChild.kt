// @Binds public alias of an unscoped @GraphPrivate binding is visible to child graphs
interface Api

class PrivateImpl : Api

@GraphExtension
interface Child {
  val api: Api
  val apiProvider: Provider<Api>

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): Child
  }
}

@DependencyGraph
interface AppGraph {
  val childFactory: Child.Factory

  @GraphPrivate @Provides fun provideImpl(): PrivateImpl = PrivateImpl()

  @Binds val PrivateImpl.bindApi: Api
}

fun box(): String {
  val child = createGraph<AppGraph>().childFactory.create()
  val first = child.api
  assertTrue(first is PrivateImpl)
  // The source is unscoped, so each read builds a new instance.
  assertNotSame(first, child.api)
  val provider = child.apiProvider
  assertNotSame(provider(), provider())
  return "OK"
}
