// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-AppGraph.json
// CHECK_REPORTS: graph-metadata/graph-AppGraph-Impl-ChildGraphImpl.json
// CHECK_REPORTS: graph-metadata/graph-AppGraph-Impl-ChildGraphImpl-GrandchildGraphImpl.json

// MODULE: api
@AssistedInject class Value(@Assisted val name: String)

@AssistedFactory
interface ValueFactory {
  fun create(name: String): Value
}

interface ParentRoots {
  @Multibinds val factories: Set<ValueFactory>
  @Multibinds fun names(): Set<String>
}

// MODULE: main(api)
@Inject class ChildValue

@GraphExtension
interface GrandchildGraph {
  val childValue: ChildValue
}

@GraphExtension
interface ChildGraph {
  @Multibinds fun names(): Set<String>
  val childValue: ChildValue
  val grandchild: GrandchildGraph
}

@DependencyGraph
interface AppGraph : ParentRoots {
  val child: ChildGraph

  companion object {
    @Provides @IntoSet fun factory(factory: ValueFactory): ValueFactory = factory
    @Provides @IntoSet fun name(): String = "parent"
  }
}
