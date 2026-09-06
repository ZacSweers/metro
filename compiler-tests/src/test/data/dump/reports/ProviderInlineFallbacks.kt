// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-AppGraph.json
// CHECK_REPORTS: graph-metadata/graph-DeferredGraph.json

// MODULE: api
interface Settings

// MODULE: hidden(api)
object HiddenSettings : Settings

// MODULE: wiring(api, hidden)
@BindingContainer
object SettingsProviders {
  // Cross-module visibility requires the generated factory for this fallback.
  @Provides internal fun provideSettings(): Settings = HiddenSettings
}

// MODULE: main(api, wiring)
@DependencyGraph(bindingContainers = [SettingsProviders::class])
interface AppGraph {
  // Direct access can't resolve HiddenSettings in this compilation.
  val settings: Settings
}

@DependencyGraph(bindingContainers = [SettingsProviders::class])
interface DeferredGraph {
  // A separate graph prevents mixed access from caching a provider for both requests.
  val settingsProvider: <!DESUGARED_PROVIDER_WARNING!>Provider<Settings><!>
}
