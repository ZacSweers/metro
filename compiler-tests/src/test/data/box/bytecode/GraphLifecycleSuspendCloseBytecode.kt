// METRO_JVM_ONLY
// ENABLE_SUSPEND_PROVIDERS

// runGraphCleanup is inline, so close() calls withContext itself.
@DependencyGraph
interface AppGraph : SuspendCloseableGraph {
  @OnGraphClose suspend fun stop() {}
}

fun box(): String = runBlocking {
  createGraph<AppGraph>().close()
  "OK"
}

// CHECK_BYTECODE_TEXT
// @AppGraph$Impl.class:
// 0 INVOKESTATIC dev/zacsweers/metro/internal/GraphCleanupKt.runGraphCleanup
// 1 INVOKESTATIC kotlinx/coroutines/BuildersKt.withContext
