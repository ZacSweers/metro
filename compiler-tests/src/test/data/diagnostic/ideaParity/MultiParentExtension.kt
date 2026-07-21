// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: keys-populated/parity/extension/multi/LeftGraph/Impl
// CHECK_REPORTS: keys-populated/parity/extension/multi/LeftGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-populated/parity/extension/multi/RightGraph/Impl
// CHECK_REPORTS: keys-populated/parity/extension/multi/RightGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-validated/parity/extension/multi/LeftGraph/Impl
// CHECK_REPORTS: keys-validated/parity/extension/multi/LeftGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-validated/parity/extension/multi/RightGraph/Impl
// CHECK_REPORTS: keys-validated/parity/extension/multi/RightGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-deferred/parity/extension/multi/LeftGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/extension/multi/LeftGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-deferred/parity/extension/multi/RightGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/extension/multi/RightGraph/Impl/ChildGraphImpl

package parity.extension.multi

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Provides

interface ParentValue

@GraphExtension
interface ChildGraph {
  val value: ParentValue
}

@DependencyGraph
interface LeftGraph {
  val childGraph: ChildGraph

  @Provides fun parentValue(): ParentValue = object : ParentValue {}
}

@DependencyGraph
interface RightGraph {
  val childGraph: ChildGraph

  @Provides fun parentValue(): ParentValue = object : ParentValue {}
}

// METRO_JVM_ONLY
