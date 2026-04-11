// KEYS_PER_GRAPH_SHARD: 1
// ENABLE_GRAPH_SHARDING: true
// REPORTS_DESTINATION: metro/reports

/*
  This test reproduces two separate crashes. Both involve graph sharding and `@AssistedInject` classes, and
  one also requires reports to be enabled.

  In some cases, `@AssistedInject` target classes get added to the list of bindings but not added directly to a given graph.
  This resulted in code that either skips over the bindings and later runs into a deferred issue, or which immediately checks
  for the binding to exist on the graph and fails. This test case triggers both of these with a use-case where the `@AssistedInject`
  class has multiple `@AssistedFactory` classes. Originally this failure was encountered with a use-case where the `@AssistedInject`
  class had a single `@AssistedFactory`. The original use-case couldn't be reproduced in a unit test, but shared the exact same
  stacktrace.

  Sharding diagnostics failure:

  Code gen exception while processing /TestGraph. No expected binding found for key MyAssistedClass. This is possibly a bug in the Metro compiler, please report it with details and/or a reproducer to https://github.com/zacsweers/metro.
  java.lang.AssertionError: Code gen exception while processing /TestGraph. No expected binding found for key MyAssistedClass. This is possibly a bug in the Metro compiler, please report it with details and/or a reproducer to https://github.com/zacsweers/metro.
  Caused by: java.lang.IllegalStateException: No expected binding found for key MyAssistedClass. This is possibly a bug in the Metro compiler, please report it with details and/or a reproducer to https://github.com/zacsweers/metro.
    at dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph.requireBinding(IrBindingGraph.kt:1056)
    at dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph.requireBinding(IrBindingGraph.kt:194)
    at dev.zacsweers.metro.compiler.ir.graph.sharding.ShardingDiagnostics.generateShardingPlanReport(ShardingDiagnostics.kt:68)


  Shard has no graph property failure:

  Code gen exception while processing /TestGraph. Shard 1 requires graph access but has no graph property
  java.lang.AssertionError: Code gen exception while processing /TestGraph. Shard 1 requires graph access but has no graph property
  Caused by: java.lang.IllegalStateException: Shard 1 requires graph access but has no graph property
    at dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator.generatePropertyAccess$lambda$0$graphAccess(GraphExpressionGenerator.kt:812)
    at dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator.generatePropertyAccess(GraphExpressionGenerator.kt:886)
    at dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator.generateBindingArguments(GraphExpressionGenerator.kt:692)
    at dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator.generateBindingCode$lambda$0$4(GraphExpressionGenerator.kt:200)
    at dev.zacsweers.metro.compiler.ir.IrMetroFactory.invokeCreatorExpression(IrMetroFactory.kt:130)
    at dev.zacsweers.metro.compiler.ir.IrMetroFactory.invokeCreateExpression(IrMetroFactory.kt:56)
    at dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator.generateBindingCode(GraphExpressionGenerator.kt:199)
    at dev.zacsweers.metro.compiler.ir.graph.IrGraphGenerator.collectShardPropertyInitializers$lambda$3(IrGraphGenerator.kt:1057)
 */

class MyAssistedClass
@AssistedInject
constructor(val service: Service, @Assisted val text: String) {
  @AssistedFactory
  interface Factory1 {
    fun create(text: String): MyAssistedClass
  }

  @AssistedFactory
  interface Factory2 {
    fun create(text: String): MyAssistedClass
  }
}

@SingleIn(AppScope::class) @Inject class Service

@Inject class Service2(val service: Service)

@Inject class Service3(val service2: Service2)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val factory1: MyAssistedClass.Factory1
  val factory2: MyAssistedClass.Factory2
  val service3: Service3
}

fun box(): String {
  val graph = createGraph<TestGraph>()
  val impl1 = graph.factory1.create("hello")
  val impl2 = graph.factory2.create("Bob")
  assertEquals(impl1.text, "hello")
  assertEquals(impl2.text, "Bob")

  return "OK"
}
