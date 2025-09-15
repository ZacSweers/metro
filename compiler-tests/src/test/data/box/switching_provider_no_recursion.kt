// TEST_TARGET: jvm
// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=2
// Test that SwitchingProvider doesn't cause infinite recursion

@Inject class Service1
@Inject class Service2(val s1: Service1)
@Inject class Service3(val s2: Service2)

@DependencyGraph
interface RecursionTestGraph {
  val service1: Service1
  val service2: Service2
  val service3: Service3
}

fun box(): String {
  val graph = createGraph<RecursionTestGraph>()

  // This should not cause StackOverflowError
  val s1 = graph.service1
  val s2 = graph.service2
  val s3 = graph.service3

  // Verify dependencies are wired correctly
  if (s2.s1 !== s1) {
    return "FAIL: service2.s1 should be the same instance as service1"
  }

  if (s3.s2 !== s2) {
    return "FAIL: service3.s2 should be the same instance as service2"
  }

  return "OK"
}