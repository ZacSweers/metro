# Inline Provider Notes

Internal notes for Metro's constant provider inlining implementation.

## Goal

Some `@Provides` declarations are just constants:

- primitive literals
- string literals
- `null`
- object singletons
- enum entries
- class literals
- `const val` reads

For these, generated graph accessors can return the value directly instead of going through the
generated `*MetroFactory` class. This reduces generated graph work and avoids unnecessary provider
factory allocation for values that the Kotlin compiler has already made constant or singleton-like.

The feature is enabled by default through `enable-provider-inlining`. It can be disabled as an
incubating kill switch.

## Eligibility

`IrInlinedProvider.fromProviderFactory(...)` is the central eligibility check. A provider can be
inlined only when it:

- has no parameters, including dispatch, extension, context, or value parameters
- is not scoped
- has a body Metro can represent as an `IrInlinedProvider.Value`

The last condition is intentionally narrow. Metro only records values that can be materialized
without running user code.

Scoped constant providers are excluded because scope annotations imply identity and caching
semantics. When provider inlining is enabled, FIR reports a warning recommending that the user
remove the scope annotation if they want the provider to be inlined.

## Metadata

Provider factory metadata carries the inlined value so cross-module providers can still be inlined.
The flow is:

- `IrProviderFactory.Metro.create(...)` computes `IrInlinedProvider` for in-round factories.
- `IrInlinedProvider.toProto()` writes it into Metro metadata.
- `BindingContainerTransformer` reads it back with `IrInlinedProvider.fromProto(...)` when the
  provider factory comes from metadata.

The compiler option is checked both when computing in-round values and when reading metadata. This
keeps `enable-provider-inlining=false` as a real disable path even if upstream metadata contains an
inlined value.

On JVM, generated provider factory classes whose value was inlined are annotated with
`@ComptimeOnly`. They still exist as synthetic declarations for metadata and compatibility, but the
annotation marks them as compile-time-only implementation detail.

## Graph Codegen

`GraphExpressionGenerator` owns the actual substitution. For `IrBinding.Provided`, it looks up the
provider factory and, when inlining is enabled, materializes the stored `IrInlinedProvider.Value`
directly into the generated graph expression.

Materialization happens per value kind:

- numeric, boolean, char, string, and null values become constants
- object values become object singleton reads
- enum values become enum entry reads
- class literal values become `KClass` references

The materialized expression is then adapted to the requested access type. For normal graph
accessors this means returning the scalar value directly. For provider access, Metro wraps
the materialized value in the appropriate factory.

## Provider Wrappers

Provider access to already-known values uses `instanceFactory(...)`. That helper chooses the
smallest runtime wrapper from the expression type:

- primitive values use `ByteFactory`, `IntFactory`, `LongFactory`, etc.
- booleans use `BooleanFactory`, which reuses the two singleton boolean factories
- other values use `InstanceFactory`

The declared field type is taken from the returned expression instead of being computed separately.
This keeps primitive factories and other concrete wrapper types visible in generated code instead of
hiding them behind `Provider<T>` when a concrete factory type is available.

## Generated Factories

Generated provider factories also preserve their concrete type through their `create()` functions.
`BindingContainerTransformer` and `InjectedClassTransformer` return the generated factory class
type from `create()` instead of widening to `Factory<T>`. This does not change factory creation for
inlined constants: generated factory `create()` functions still return generated factory instances,
and inlining remains a graph expression optimization.

Value-class provider factories are intentionally not generated today. Fir2Ir computes value-class
representation from FIR declarations, while Metro currently creates regular factory constructors
and fields in IR. Declaring a factory as a value class in FIR without also declaring its backing
constructor/property in FIR leaves Fir2Ir with no representation to lower.

Future value-class factory support can either generate the backing FIR member declarations alongside
the factory class declaration, or wait for Kotlin compiler plugin support for generating new classes
directly in IR. Until then, Metro still preserves concrete generated factory return types where that
does not change factory construction behavior.

## Bound Instances

Bound instances are handled separately from computed provider bindings. The graph already stores
their scalar value in an instance field. A single provider access can cheaply wrap that field at the
use site, so Metro does not create an extra provider field for one provider reference.

`BindingPropertyCollector` owns the caching decision:

- multiple provider references to the same bound instance reserve a cached provider context key
- one provider reference does not reserve a provider field
- computed bindings still cache provider fields for mixed scalar/provider use, because otherwise
  Metro would generate the binding expression twice

`IrGraphGenerator` consumes the collector's `cachedProviderContextKeys` result. It creates bound
instance provider fields only for keys the collector reserved.
