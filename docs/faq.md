# FAQ

This is a list of frequently asked questions about Metro. Consider also searching the issue tracker and discussions section of the Metro repo for anything not covered here!

### **Compiler plugins are not a stable API, is Metro safe to use?**

This is a fair question! Metro will often require new companion releases for each Kotlin release. This is a part of life when using compiler plugins. That said, Kotlin does extensive beta/RC cycles that Metro will test against and turn around new releases within a day or two barring any unexpected circumstances (or vacation!)

The harder issue is going to be IDE support, as the Kotlin IDE plugin branches independently from regular Kotlin releases. Right now the answer is "YMMV", but we're exploring a couple solutions for this to ensure better stability.

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