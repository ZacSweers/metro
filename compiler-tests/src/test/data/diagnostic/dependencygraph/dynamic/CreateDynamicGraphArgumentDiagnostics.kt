// RENDER_DIAGNOSTICS_FULL_TEXT
@DependencyGraph
interface ExampleGraph

@DependencyGraph
interface ExampleGraphWithFactory {
  @DependencyGraph.Factory
  interface Factory {
    fun create(): ExampleGraphWithFactory
  }
}

@BindingContainer
object Bindings

fun noArgErrors() {
  createDynamicGraph<<!CREATE_DYNAMIC_GRAPH_ERROR!>ExampleGraph<!>>()
  createDynamicGraphFactory<<!CREATE_DYNAMIC_GRAPH_ERROR!>ExampleGraphWithFactory.Factory<!>>().create()
}

fun spreadArgErrors() {
  createDynamicGraph<ExampleGraph>(<!CREATE_DYNAMIC_GRAPH_ERROR!>*arrayOf(Bindings)<!>)
  createDynamicGraphFactory<ExampleGraphWithFactory.Factory>(<!CREATE_DYNAMIC_GRAPH_ERROR!>*arrayOf(Bindings)<!>).create()
}

fun arraysOnAsThemselvesErrors(vararg containers: Any) {
  createDynamicGraph<ExampleGraph>(<!CREATE_DYNAMIC_GRAPH_ERROR!>containers = containers<!>)
  createDynamicGraphFactory<ExampleGraphWithFactory.Factory>(<!CREATE_DYNAMIC_GRAPH_ERROR!>containers = containers<!>).create()
}

fun failureCases() {
  // Duplicate classes
  createDynamicGraph<ExampleGraph>(<!CREATE_DYNAMIC_GRAPH_ERROR!>Bindings<!>, <!CREATE_DYNAMIC_GRAPH_ERROR!>Bindings<!>)
  createDynamicGraphFactory<ExampleGraphWithFactory.Factory>(<!CREATE_DYNAMIC_GRAPH_ERROR!>Bindings<!>, <!CREATE_DYNAMIC_GRAPH_ERROR!>Bindings<!>).create()

  // Not binding containers
  createDynamicGraph<ExampleGraph>(<!CREATE_DYNAMIC_GRAPH_ERROR!>"Bindings"<!>)
  createDynamicGraphFactory<ExampleGraphWithFactory.Factory>(<!CREATE_DYNAMIC_GRAPH_ERROR!>"Bindings"<!>).create()

  // Anonymous/local classes
  createDynamicGraph<ExampleGraph>(
    <!BINDING_CONTAINER_ERROR!>@BindingContainer<!>
    <!CREATE_DYNAMIC_GRAPH_ERROR!>object : Any()<!> {

    }
  )
  createDynamicGraphFactory<ExampleGraphWithFactory.Factory>(
    <!BINDING_CONTAINER_ERROR!>@BindingContainer<!>
    <!CREATE_DYNAMIC_GRAPH_ERROR!>object : Any()<!> {

    }
  ).create()
  val anonymousInstance =
    <!BINDING_CONTAINER_ERROR!>@BindingContainer<!>
    object : Any() {

    }
  createDynamicGraph<ExampleGraph>(<!CREATE_DYNAMIC_GRAPH_ERROR!>anonymousInstance<!>)
  createDynamicGraphFactory<ExampleGraphWithFactory.Factory>(<!CREATE_DYNAMIC_GRAPH_ERROR!>anonymousInstance<!>).create()

  <!BINDING_CONTAINER_ERROR!>@BindingContainer<!>
  class LocalClass
  createDynamicGraph<ExampleGraph>(<!CREATE_DYNAMIC_GRAPH_ERROR!>LocalClass()<!>)
  createDynamicGraphFactory<ExampleGraphWithFactory.Factory>(<!CREATE_DYNAMIC_GRAPH_ERROR!>LocalClass()<!>).create()
}

@BindingContainer
class InstanceContainer

fun okCases() {
  createDynamicGraph<ExampleGraph>(
    Bindings,
    InstanceContainer(),
  )
  createDynamicGraphFactory<ExampleGraphWithFactory.Factory>(
    Bindings,
    InstanceContainer(),
  ).create()
}
