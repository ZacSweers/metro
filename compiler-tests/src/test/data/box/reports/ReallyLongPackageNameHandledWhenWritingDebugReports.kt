// https://github.com/ZacSweers/metro/issues/1303
/**
 * Covers the case where debug report file names exceed the file name limit when we have the combination of long package and long name, e.g.
 * java.nio.file.FileSystemException: /private/var/folders/wv/bkgjhyrj3l5f8pwkh_kfds000000gn/T/dev.zacsweers.metro.compiler.BoxTestGenerated$Reports2testReallyLongPackageNameHandledWhenWritingDebugReports/metro/reports/provider-factory-anvil.register.featureflags.com.squareup.feature.featureflags.RealProductionLoremIpsumFunctionalityFeatureFlagWrapper_AllowLoremIpsumFunctionalitySemiLongNameFeatureFlag.ProvidesAllowLoremIpsumFunctionalitySemiLongNameFeatureFlag$$MetroFactory.kt: File name too long
 * 	< ... java internals stacktrace ... >
 * 	at dev.zacsweers.metro.compiler.ir.IrMetroContextKt.writeDiagnostic(IrMetroContext.kt:265)
 *
 * 	In this example, the default report prefix and package name add up to almost 80 characters:
 * 	provider-factory-anvil.register.featureflags.com.squareup.feature.featureflags
 *
 * 	This isn't exclusively a package issue, because it's still a combination of the related factory class name, but encoding the package as a report path segment instead of the file name is a simple way to significantly limit the cases where this happens without reducing generated code or file readability.
 */
// REPORTS_DESTINATION: metro/reports
// MODULE: lib

// FILE: FeatureFlag.kt
package com.squareup.featureflags

interface FeatureFlag

// FILE: ContributedFeatureFlag.kt
package com.squareup.feature.featureflags

import com.squareup.featureflags.FeatureFlag

object RealProductionLoremIpsumFunctionalityFeatureFlagWrapper {
  object AllowLoremIpsumFunctionalitySemiLongNameFeatureFlag : FeatureFlag
}

// FILE: GeneratedBindingContainer.kt
package anvil.register.featureflags.com.squareup.feature.featureflags

import com.squareup.feature.featureflags.RealProductionLoremIpsumFunctionalityFeatureFlagWrapper
import com.squareup.featureflags.FeatureFlag

// In this example, this binding container would normally be Anvil- or KSP-generated code based on the above feature flag and a custom contribution annotation
@BindingContainer
@ContributesTo(scope = AppScope::class)
public object RealProductionLoremIpsumFunctionalityFeatureFlagWrapper_AllowLoremIpsumFunctionalitySemiLongNameFeatureFlag {
  @Provides
  @IntoSet
  public fun providesAllowLoremIpsumFunctionalitySemiLongNameFeatureFlag(): FeatureFlag = RealProductionLoremIpsumFunctionalityFeatureFlagWrapper.AllowLoremIpsumFunctionalitySemiLongNameFeatureFlag
}

// MODULE: main(lib)
package test

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import com.squareup.featureflags.FeatureFlag

@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface ExampleGraph {
  val flags: Set<FeatureFlag>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(graph.flags.size, 1)
  return "OK"
}
