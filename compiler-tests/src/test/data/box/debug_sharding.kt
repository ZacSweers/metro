// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=2 -Xplugin-option=metro:log=true -Xplugin-option=metro:debug=true
// TEST_TARGET: jvm

// Debug test to isolate sharding issue
@Inject class SimpleService

@DependencyGraph
interface DebugGraph {
  val service: SimpleService
}

fun box(): String {
  val graph = createGraph<DebugGraph>()

  // Check what type is being returned
  val service = graph.service

  if (service is SimpleService) {
    return "OK"
  } else {
    return "FAIL: Expected SimpleService but got ${service::class.simpleName}"
  }
}