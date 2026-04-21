// RENDER_DIAGNOSTICS_FULL_TEXT
// DESUGARED_PROVIDER_SEVERITY: WARN

class Foo

@Inject
class UsesDesugaredProvider(
  // Preferred function-syntax form, no diagnostic
  val sugared: () -> Foo,
  // Desugared Provider<T> form should warn
  val desugared: <!DESUGARED_PROVIDER_WARNING!>Provider<Foo><!>,
  // Provider<Lazy<T>> is still discouraged
  val providerOfLazy: <!DESUGARED_PROVIDER_WARNING!>Provider<Lazy<Foo>><!>,
  // () -> Lazy<T> uses the function-syntax form, no diagnostic
  val sugaredOfLazy: () -> Lazy<Foo>,
  // Plain Lazy<T> is not a provider, no diagnostic
  val lazy: Lazy<Foo>,
  // Map<K, Provider<V>> nests a desugared provider and should warn
  val mapProvider: <!DESUGARED_PROVIDER_WARNING!>Map<String, Provider<Foo>><!>,
  // Map<K, V> with canonical value types should not warn
  val map: Map<String, Foo>,
  // Map<K, () -> V> uses the function-syntax form, no diagnostic
  val mapSugared: Map<String, () -> Foo>,
)
