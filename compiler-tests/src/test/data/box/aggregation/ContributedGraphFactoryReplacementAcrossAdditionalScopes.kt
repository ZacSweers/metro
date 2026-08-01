abstract class OriginalScope private constructor()

abstract class VariantScope private constructor()

abstract class OriginalChildScope private constructor()

abstract class VariantChildScope private constructor()

interface ChildGraph {
  interface Factory {
    fun create(): ChildGraph
  }
}

@GraphExtension(OriginalChildScope::class)
interface OriginalChildGraph : ChildGraph {
  @ContributesTo(OriginalScope::class)
  @GraphExtension.Factory
  interface Factory : ChildGraph.Factory {
    override fun create(): OriginalChildGraph
  }
}

@GraphExtension(
  scope = VariantChildScope::class,
  additionalScopes = [OriginalChildScope::class],
)
interface VariantChildGraph : ChildGraph {
  @ContributesTo(
    scope = VariantScope::class,
    replaces = [OriginalChildGraph.Factory::class],
  )
  @GraphExtension.Factory
  interface Factory : ChildGraph.Factory {
    override fun create(): VariantChildGraph
  }
}

@GraphExtension(
  scope = VariantScope::class,
  additionalScopes = [OriginalScope::class],
)
interface VariantGraph {
  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createVariantGraph(): VariantGraph
  }
}

@DependencyGraph(AppScope::class)
interface RootGraph

fun box(): String {
  val graph = createGraph<RootGraph>().createVariantGraph()
  val factory = graph as ChildGraph.Factory
  assertIs<VariantChildGraph>(factory.create())
  return "OK"
}
