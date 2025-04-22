Changelog
=========

**Unreleased**
--------------

- **Fix**: Fix `@Provides` graph parameters not getting passed on to extended child graphs.

0.2.0
-----

_2025-04-21_

- **New**: Nullable bindings are now allowed! See the [nullability docs](https://zacsweers.github.io/metro/bindings#nullability) for more info.
- **Enhancement**: Add diagnostics for multibindings with star projections.
- **Enhancement**: Add diagnostic for map multibindings with nullable keys.
- **Fix**: Ensure assisted factories' target bindings' parameters are processed in MetroGraph creation. Previously, these weren't processed and could result in scoped bindings not being cached.
- **Fix**: Fix duplicate field accessors generated for graph supertypes.
- **Change**: `InstanceFactory` is no longer a value class. This wasn't going to offer much value in practice.
- Add [compose navigation sample](https://github.com/ZacSweers/metro/tree/main/samples/compose-navigation-app).

Special thanks to  [@bnorm](https://github.com/bnorm) and [@yschimke](https://github.com/yschimke) for contributing to this release!

0.1.3
-----

_2025-04-18_

- **Change**: Multibindings may not be empty by default. To allow an empty multibinding, `@Multibinds(allowEmpty = true)` must be explicitly declared now.
- **New**: Write graph metadata to reports (if enabled).
- **New**: Support configuring debug and reports globally via `metro.debug` and `metro.reportsDestination` Gradle properties (respectively).
- **Enhancement**: Change how aggregation hints are generated to improve incremental compilation. Externally contributed hints are now looked up lazily per-scope instead of all at once.
- **Enhancement**: Optimize empty map multibindings to reuse a singleton instance.
- **Enhancement**: Report error diagnostic if Dagger's `@Reusable` is used on a provider or injected class.
- **Enhancement**: Tweak diagnostic error strings for members so that IDE terminals auto-link them better. i.e., instead of printing `example.AppGraph.provideString`, Metro will print `example.AppGraph#provideString` instead.
- **Enhancement**: Support repeatable @ContributesBinding annotations with different scopes.
- **Fix**: Fix incremental compilation when `@Includes`-annotated graph parameters change accessor signatures.
- **Fix**: Don't allow graph extensions to use the same scope as any extended ancestor graphs.
- **Fix**: Don't allow multiple ancestor graphs of graph extensions to use the same scope.
- **Fix**: Handle scenarios where the compose-compiler plugin runs _before_ Metro's when generating wrapper classes for top-level `@Composable` functions.
- **Fix**: Fix an edge case in graph extensions where child graphs would miss a provided scoped binding in a parent graph that was also exposed as an accessor.
- **Fix**: Fix Dagger interop issue when calling Javax/Jakarta/Dagger providers from Metro factories.
- **Fix**: Fix Dagger interop issue when calling `dagger.Lazy` from Metro factories.
- **Fix**: Preserve the original `Provider` or `Lazy` type used in injected types when generating factory creators.
- Temporarily disable hint generation in WASM targets to avoid file count mismatches until [KT-75865](https://youtrack.jetbrains.com/issue/KT-75865).
- Add an Android sample: https://github.com/ZacSweers/metro/tree/main/samples/android-app
- Add a multiplatform Circuit sample: https://github.com/ZacSweers/metro/tree/main/samples/circuit-app
- Add samples docs: https://zacsweers.github.io/metro/samples
- Add FAQ docs: https://zacsweers.github.io/metro/faq

Special thanks to [@JoelWilcox](https://github.com/JoelWilcox), [@bnorm](https://github.com/bnorm), and [@japplin](https://github.com/japplin) for contributing to this release!

0.1.2
-----

_2025-04-08_

- **Enhancement**: Implement `createGraph` and `createGraphFactory` FIR checkers for better error diagnostics on erroneous type arguments.
- **Enhancement**: Add `ContributesBinding.rank` interop support with Anvil.
- **Enhancement**: Check Kotlin version compatibility. Use the `metro.version.check=false` Gradle property to disable these warnings if you're feeling adventurous.
- **Fix**: Fix class-private qualifiers on multibinding contributions in other modules not being recognized in downstream graphs.
- **Fix**: Fix member injectors not getting properly visited in graph validation.
- **Fix**: Fix a bug where `Map<Key, Provider<Value>>` multibindings weren't always unwrapped correctly.
- **Fix**: Fix `Map<Key, Provider<Value>>` type keys not correctly interpreting the underlying type key as `Map<Key, Value>`.
- **Change**: Change `InstanceFactory` to a value class.
- **Change**: Make `providerOf` use `InstanceFactory` under the hood.

Special thanks to [@JoelWilcox](https://github.com/JoelWilcox), [@bnorm](https://github.com/bnorm), [@japplin](https://github.com/japplin), [@kevinguitar](https://github.com/kevinguitar), and [@erawhctim](https://github.com/erawhctim) for contributing to this release!

0.1.1
-----

_2025-04-03_

Initial release!

See the announcement blog post: https://www.zacsweers.dev/introducing-metro/
