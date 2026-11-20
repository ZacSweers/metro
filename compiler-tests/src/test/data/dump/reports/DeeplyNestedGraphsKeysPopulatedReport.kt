// RUN_PIPELINE_TILL: BACKEND
// This fixture checks the report contents at each level of nesting.
// The DeeplyNestedGraphsAreHandledWhenGeneratingReports box test also exercises filename limits.

// CHECK_REPORTS: keys-populated/AppGraph/Impl
// CHECK_REPORTS: keys-populated/AppGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-populated/AppGraph/Impl/ChildGraphImpl/GrandChildGraphImpl
// CHECK_REPORTS: keys-populated/AppGraph/Impl/ChildGraphImpl/GrandChildGraphImpl/GreatGrandChildGraphImpl
// CHECK_REPORTS: keys-populated/AppGraph/Impl/ChildGraphImpl/GrandChildGraphImpl/GreatGrandChildGraphImpl/GreaterGrandChildGraphImpl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

@GraphExtension interface GreaterGrandChildGraph

@GraphExtension
interface GreatGrandChildGraph {
  val child: GreaterGrandChildGraph
}

@GraphExtension
interface GrandChildGraph {
  val child: GreatGrandChildGraph
}

@GraphExtension
interface ChildGraph {
  val child: GrandChildGraph
}

@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val child: ChildGraph
}
