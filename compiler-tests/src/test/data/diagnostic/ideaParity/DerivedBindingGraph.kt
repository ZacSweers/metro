// ENABLE_DERIVED_BINDINGS
// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-derived-AppGraph.json
// CHECK_REPORTS: graph-metadata/graph-parity-derived-AppGraph-Impl-ChildGraphImpl.json
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: keys-populated/parity/derived/AppGraph/Impl
// CHECK_REPORTS: keys-populated/parity/derived/AppGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-validated/parity/derived/AppGraph/Impl
// CHECK_REPORTS: keys-validated/parity/derived/AppGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-deferred/parity/derived/AppGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/derived/AppGraph/Impl/ChildGraphImpl

package parity.derived

import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides

interface Encoder<T>

class User

class SimpleEncoder<T> : Encoder<T>

interface Cache<T>

@Inject class LruCache<T> : Cache<T>

@BindingContainer
object Encoders {
  @Provides fun string(): Encoder<String> = SimpleEncoder()

  @Provides fun user(): Encoder<User> = SimpleEncoder()

  @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = SimpleEncoder()

  @Provides fun <T : Any> nullable(inner: Encoder<T>): Encoder<T?> = SimpleEncoder()
}

@BindingContainer
interface CacheBindings {
  @Binds fun <T> lru(impl: LruCache<T>): Cache<T>
}

@GraphExtension
interface ChildGraph {
  val userLists: Encoder<List<List<User>>>
  val userCache: Cache<User>
}

@DependencyGraph(bindingContainers = [Encoders::class, CacheBindings::class])
interface AppGraph {
  val users: Encoder<List<User>>
  val nullableUser: Encoder<User?>
  val stringCache: Cache<String>
  val child: ChildGraph
}

// METRO_JVM_ONLY
