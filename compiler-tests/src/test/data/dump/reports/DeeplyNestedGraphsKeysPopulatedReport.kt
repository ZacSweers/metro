// Similar to https://github.com/ZacSweers/metro/issues/1303
/**
 * Covers the case where debug report file names exceed the file name limit when we have a deeply nested graph, e.g.
 * Caused by: java.nio.file.FileSystemException: /private/var/folders/wv/bkgjhyrj3l5f8pwkh_kfds000000gn/T/dev.zacsweers.metro.compiler.ReportsTestGeneratedtestDeeplyNestedGraphsHandledWhenWritingDebugReports/metro/reports/keys-populated/ProductDevAppDependencyGraph_Impl_ProductDevLoggedInComponentImpl_ProductDevMainActivityComponentImpl_EditEstimateConfigureItemScopeComponentImpl_ConfigureItemComponentImpl_ProductConfigureItemDetailScreenComponentImpl_ConfigureItemConfirmationScreenComponentImpl.txt: File name too long
 * 	< ... java internals stacktrace ... >
 * 	at dev.zacsweers.metro.compiler.ir.IrMetroContextKt.writeDiagnostic(IrMetroContext.kt:293)
 *
 * 	In this example, the nested class name is already 264 characters before factoring in any unique diagnostic report key or package name:
 * 	ProductDevAppDependencyGraph_Impl_ProductDevLoggedInComponentImpl_ProductDevMainActivityComponentImpl_EditEstimateConfigureItemScopeComponentImpl_ConfigureItemComponentImpl_ProductConfigureItemDetailScreenComponentImpl_ConfigureItemConfirmationScreenComponentImpl
 */
// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl
// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl/ProductDevLoggedInComponentImpl
// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl/ProductDevLoggedInComponentImpl/ProductDevMainActivityComponentImpl
// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl/ProductDevLoggedInComponentImpl/ProductDevMainActivityComponentImpl/EditEstimateConfigureItemScopeComponentImpl
// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl/ProductDevLoggedInComponentImpl/ProductDevMainActivityComponentImpl/EditEstimateConfigureItemScopeComponentImpl/ConfigureItemComponentImpl
// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl/ProductDevLoggedInComponentImpl/ProductDevMainActivityComponentImpl/EditEstimateConfigureItemScopeComponentImpl/ConfigureItemComponentImpl/ProductConfigureItemDetailScreenComponentImpl
// CHECK_REPORTS: keys-populated/ProductDevAppDependencyGraph/Impl/ProductDevLoggedInComponentImpl/ProductDevMainActivityComponentImpl/EditEstimateConfigureItemScopeComponentImpl/ConfigureItemComponentImpl/ProductConfigureItemDetailScreenComponentImpl/ConfigureItemConfirmationScreenComponentImpl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

@GraphExtension
interface ConfigureItemConfirmationScreenComponent

@GraphExtension
interface ProductConfigureItemDetailScreenComponent {
  val child: ConfigureItemConfirmationScreenComponent
}

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
