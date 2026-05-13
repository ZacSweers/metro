// This test is complementary to the [DeeplyNestedGraphsAreHandledWhenGeneratingReports] box test,
// which verifies a sufficiently nested + long-named graph structure does not run into 'file name
// too long' exceptions for the report files. We are not able to test the same behavior here because
// defining a sufficiently nested graph in a dump test results in a 'file name too long' exception
// for an equivalent '.class' file reference in the generated Java [ReportsTestGenerated] file.

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
