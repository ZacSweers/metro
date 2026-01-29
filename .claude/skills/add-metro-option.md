---
name: add-compiler-option
description: Adds a new compiler option to Metro.
---

## Files to Update

When adding a new option, you need to update these files in order:

### 1. `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/MetroOptions.kt`

Add the option in four places:

#### a. Add enum entry in `MetroOption`

```kotlin
MY_NEW_OPTION(
  RawMetroOption.boolean(  // or RawMetroOption() for non-boolean types
    name = "my-new-option",  // kebab-case name used in CLI
    defaultValue = false,
    valueDescription = "<true | false>",
    description = "Description of what this option does.",
    required = false,
    allowMultipleOccurrences = false,
  )
),
```

For non-boolean options, use the full `RawMetroOption` constructor with a `valueMapper`:
```kotlin
MY_INT_OPTION(
  RawMetroOption(
    name = "my-int-option",
    defaultValue = 10,
    valueDescription = "<count>",
    description = "Description here",
    required = false,
    allowMultipleOccurrences = false,
    valueMapper = { it.toInt() },
  )
),
```

#### b. Add property in `MetroOptions` data class

```kotlin
public val myNewOption: Boolean = MetroOption.MY_NEW_OPTION.raw.defaultValue.expectAs(),
```

#### c. Add property in `MetroOptions.Builder`

```kotlin
public var myNewOption: Boolean = base.myNewOption
```

#### d. Add to `Builder.build()` function

```kotlin
myNewOption = myNewOption,
```

#### e. Add to `MetroOptions.Companion.load()` function

```kotlin
MY_NEW_OPTION -> myNewOption = configuration.getAsBoolean(entry)
```

For non-boolean types, use the appropriate helper:
- `configuration.getAsString(entry)` for String
- `configuration.getAsInt(entry)` for Int
- `configuration.getAsSet(entry)` for Set types

### 2. `gradle-plugin/src/main/kotlin/dev/zacsweers/metro/gradle/MetroPluginExtension.kt`

Add a Gradle DSL property:

```kotlin
/**
 * KDoc description of what this option does.
 *
 * Disabled by default.
 */
public val myNewOption: Property<Boolean> =
  objects.booleanProperty("metro.myNewOption", false)
```

For options that should support gradle properties:
```kotlin
objects.booleanProperty("metro.propertyName", defaultValue)
```

For options without gradle property support:
```kotlin
objects.property(Boolean::class.java).convention(false)
```

### 3. `gradle-plugin/src/main/kotlin/dev/zacsweers/metro/gradle/MetroGradleSubplugin.kt`

In `applyToCompilation`, add to the options list:

```kotlin
add(lazyOption("my-new-option", extension.myNewOption))
```

Note: The option name here must match the `name` in `RawMetroOption`.

### 4. `compiler/src/test/kotlin/dev/zacsweers/metro/compiler/MetroCompilerTest.kt`

In `toPluginOptions()`, add handling for the new option:

```kotlin
MetroOption.MY_NEW_OPTION -> {
  processor.option(entry.raw.cliOption, myNewOption)
}
```

### 5. (Optional) `compiler-tests/src/test/kotlin/dev/zacsweers/metro/compiler/MetroDirectives.kt`

Only needed if the option should be controllable from test directives:

```kotlin
val MY_NEW_OPTION by
  valueDirective("Description of the directive.") { it.toBoolean() }
```

Or for simple on/off directives:
```kotlin
val MY_NEW_OPTION by directive("Description of the directive.")
```

## Option Types

- **Boolean**: Use `RawMetroOption.boolean()` helper
- **Int**: Use `RawMetroOption()` with `valueMapper = { it.toInt() }`
- **String**: Use `RawMetroOption()` with `valueMapper = { it }`
- **Enum**: Use `RawMetroOption()` with `valueMapper = { it }` and parse in load()
- **Set<ClassId>**: Use `RawMetroOption()` with `valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } }`

## Naming Conventions

- Enum entry: `SCREAMING_SNAKE_CASE`
- CLI option name: `kebab-case`
- Kotlin property: `camelCase`
- Gradle property: `metro.camelCase`

## Annotations

- Use `@DelicateMetroGradleApi` for experimental options
- Use `@Deprecated` for options that will be removed
