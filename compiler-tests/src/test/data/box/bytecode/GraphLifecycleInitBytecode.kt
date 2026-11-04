// METRO_JVM_ONLY

private var starts = 0

@DependencyGraph interface AppGraph : CloseableGraph {
  @OnGraphInit fun start() { starts++ }
}

fun box(): String {
  createGraph<AppGraph>()
  check(starts == 1)
  return "OK"
}

// CHECK_BYTECODE_TEXT
// @AppGraph$Impl.class:
// 1 INVOKE(VIRTUAL|INTERFACE) .*\.start \(\)V
