# FAQ

This is a list of frequently asked questions about Metro. Consider also searching the issue tracker and discussions section of the Metro repo for anything not covered here!

### **Compiler plugins are not a stable API, is Metro safe to use?**

This is a fair question! Often times, compiler plugins require new companion releases for each Kotlin release. This is a part of life when using compiler plugins.

Metro takes a slightly different approach to this and tries to support forward compatibility on a best-effort basis. Usually, it's `N+.2` (so a Metro version built against Kotlin `2.3.0` will try to support up to `2.3.20`). This allows Metro to support a reasonable range of Kotlin releases across compiler and IDE versions. See the [compatibility docs](compatibility.md) for more details.

### **Metro is not a stable API, is Metro safe to use?**

Yes, Metro is _functionally_ stable and ready for production use. Its runtime and Gradle plugin APIs are not yet _stabilized_, which is not the same as being unstable for use

See the [stability docs](stability.md) for more details.

### **Why doesn't Metro support `@Reusable`?**

!!! tip "Some technical context"
    `@Reusable` works almost identically in code gen as scoped types, it just uses `SingleCheck` instead of `DoubleCheck`. It's basically like using `lazy(NONE)` instead of `lazy(SYNCHRONIZED)`.

A few different reasons Metro doesn't have it

- I think it risks being like `@Stable` in compose where people chase it for perceived performance benefits that they have not profiled or would not actualize if they did. Basically it becomes a premature optimization vector
    - Ron Shapiro (the author of it) even said you shouldn't use it or scoping in general [for performance reasons] unless you've measured it: https://medium.com/@shapiro.rd/reusable-has-many-of-the-same-costs-as-singleton-c20b5d1ef308
- Most people don't really know when to use it. It doesn't really strike a balance so much as blurs the line for limited value (see: the first bullet).
- It invites people to make unclear assumptions. It's pretty simple to assume something stateful is always a new instance or always the same scoped instance. It is harder to envision scenarios where you have stateful types where you don't care about knowing if it's shared or not. You could say this should only be for stateless types then, but then you're deciding...
    - Do you want to limit instances? Just scope it
    - Do you not care about limiting instances? Don't scope it
- What's the expected behavior if you have a `@Reusable` type `Thing` and then request a `Lazy<Thing>` elsewhere? Currently, Metro `DoubleCheck.lazy(...)`'s whatever binding provides it at the injection site, which would then defeat this. To undo that, Metro would need to introduce some means of indicating "what kind" of `Lazy` is needed, which just complicates things for the developer.

### **Why doesn't Metro support kotlin-inject-style `@IntoMap` bindings?**

!!! tip "Some technical context"
    kotlin-inject allows you to provide key/value pairs from an `@IntoMap` function rather than use `@MapKey` annotations.

This allows some dynamism with keys but has some downsides. A few different reasons Metro doesn't use this approach

- Duplicate key checking becomes a runtime failure rather than compile-time.
- It breaks the ability to expose `Map<Key, Provider<Value>>` unless you start manually managing `Provider` types yourself.
- You allocate and throw away a `Pair` instance each time it's called.

## Hilt FAQ

### **Will Metro add support for Hilt features or Hilt interop?**

Metro is largely inspired by Dagger and Anvil, but not Hilt. Hilt works in different ways and has different goals. Hilt is largely focused around supporting android components and relies heavily on subcomponents to achieve this.

Some features overlap but just work differently in Metro:

- Instead of `@UninstallModules` and `@TestInstallIn`, Metro graphs can exclude aggregations and contributed bindings can replace other bindings.
- Hilt has support for injecting `ViewModel`s, but this is entirely doable without Hilt as well by just creating a multibinding. See the [android-app](https://github.com/ZacSweers/metro/tree/main/samples/android-app) sample for an example.
- Hilt has support for aggregation with `@InstallIn`, Metro uses `@Contributes*` annotations.

Some features are focused around injecting Android framework components. There are two arguably better solutions to this and one not-better solution.

1. (Not better) Expose injector functions on a graph to do member injection directly from the graph.
2. (Better) Constructor-inject these types using `AppComponentFactory`. This does require minSdk 28. When Hilt was first released in 2020, this was a relatively new API. However, 2020 was a long time ago! minSdk 28+ is much more common today, making this much more feasible of a solution.
3. (Best) Use an app architecture that better abstracts away the surrounding Android framework components, making them solely entry points.

The rest of Hilt's features focus on gluing these pieces together and also supporting Java (which Metro doesn't support).

### How can I replicate Hilt's `@HiltAndroidTest`?

!!! tip "Some technical context"
    Hilt's `@HiltAndroidTest` and associated rule allow tests to "replace" bindings in a target graph even if it's compiled in another project.

Metro supports dynamic replacements via a similar feature called [dynamic graphs](https://zacsweers.github.io/metro/latest/dependency-graphs/#dynamic-graphs).

### Can Metro do Hilt's automatic aggregation of transitive dependencies' aggregated bindings?

!!! tip "Some technical context"
    Hilt can automatically aggregate transitive dependencies' contributed bindings even if they are not explicitly visible to the consuming project.

In short: no. The fact that Hilt does this is a bad thing in my opinion. It essentially defeats the purpose of incremental compilation because you have to write your own version in a dedicated classpath scanning Gradle task that then generates stub sources for all the stuff you were trying to hide upstream.

In Metro, you must play by kotlinc's native incremental compilation rules. If you want to aggregate bindings from transitive dependencies, you must expose them in that project (i.e. Gradle `api` dependencies) or explicitly declare those dependencies in the consuming project.