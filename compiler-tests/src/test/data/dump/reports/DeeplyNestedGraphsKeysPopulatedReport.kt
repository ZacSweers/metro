// This test is complementary to the [DeeplyNestedGraphsAreHandledWhenGeneratingReports] box test,
// which verifies a sufficiently nested graph structure does not run into 'file name too long'
// exceptions for the report files. We are not able to test the same behavior here because defining
// a sufficiently nested graph in a dump test results in a 'file name too long' exception for an
// equivalent '.class' file reference in the generated Java [ReportsTestGenerated] file.

// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl
// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl/ProductDevLoggedInComponentImpl
// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl/ProductDevLoggedInComponentImpl/ProductDevMainActivityComponentImpl
// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl/ProductDevLoggedInComponentImpl/ProductDevMainActivityComponentImpl/EditEstimateConfigureItemScopeComponentImpl
// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl/ProductDevLoggedInComponentImpl/ProductDevMainActivityComponentImpl/EditEstimateConfigureItemScopeComponentImpl/ConfigureItemComponentImpl
// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl/ProductDevLoggedInComponentImpl/ProductDevMainActivityComponentImpl/EditEstimateConfigureItemScopeComponentImpl/ConfigureItemComponentImpl/ProductConfigureItemDetailScreenComponentImpl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

@GraphExtension
interface ProductConfigureItemDetailScreenComponent

@GraphExtension
interface ConfigureItemComponent {
  val child: ProductConfigureItemDetailScreenComponent
}

@GraphExtension
interface EditEstimateConfigureItemScopeComponent {
  val child: ConfigureItemComponent
}

@GraphExtension
interface ProductDevMainActivityComponent {
  val child: EditEstimateConfigureItemScopeComponent
}

@GraphExtension
interface ProductDevLoggedInComponent {
  val child: ProductDevMainActivityComponent
}

@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface ProductDevAppDependencyGraph {
  val child: ProductDevLoggedInComponent
}
