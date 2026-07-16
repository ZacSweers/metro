// RENDER_DIAGNOSTICS_FULL_TEXT
// DESUGARED_PROVIDER_SEVERITY: WARN
// ENABLE_SUSPEND_PROVIDERS
@file:Suppress("OPT_IN_USAGE")

class Foo

@Inject
class UsesDesugaredSuspendProvider(
  // Preferred suspend function-syntax form, no diagnostic
  val sugared: suspend () -> Foo,
  // Desugared SuspendProvider<T> form should warn
  val desugared: <!DESUGARED_PROVIDER_WARNING!>SuspendProvider<Foo><!>,
)
