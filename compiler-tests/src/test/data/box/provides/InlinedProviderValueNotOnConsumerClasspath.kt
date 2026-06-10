// https://github.com/ZacSweers/metro/issues/2423
// An object provider's value class may not be resolvable in consuming compilations, e.g. when the
// object comes from an implementation dependency of the provider's module. Materialization must
// fall back to the provider factory instead of inlining in that case.

// MODULE: api
interface Settings

// MODULE: hidden(api)
object HiddenSettings : Settings

// MODULE: wiring(api, hidden)
@BindingContainer
object SettingsProviders {
  @Provides fun provideSettings(): Settings = HiddenSettings
}

// MODULE: main(api, wiring)
@DependencyGraph(bindingContainers = [SettingsProviders::class])
interface AppGraph {
  val settings: Settings
  val settingsProvider: Provider<Settings>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val settings = graph.settings
  if (settings::class.simpleName != "HiddenSettings") return "Fail: unexpected settings $settings"
  if (settings !== graph.settingsProvider()) return "Fail: provider accessor"
  return "OK"
}
