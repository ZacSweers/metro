# 🚇 Metro

# Introduction

Metro is a compile-time dependency injection framework that draws heavy inspiration from [Dagger](https://github.com/google/dagger), [Anvil](https://github.com/square/anvil), and [Kotlin-Inject](https://github.com/evant/kotlin-inject). It seeks to unify their best features under one, cohesive solution while adding a few new features and implemented as a compiler plugin.

### Why another DI framework?

It’s felt for some time like the Kotlin community has been waiting for a library at the intersection of these different tools and features. Different tools exist for parts of these, but there’s not yet been a unified solution that checks all these boxes, leaves behind some of these tools’ limitations, and embraces newer features that native compiler plugins offer.

In short, Metro stands on the shoulders of giants. It doesn’t try to reinvent the wheel, it does try to make those wheels work better together.

!!! note
    _I’m aware of the [XKCD comic](https://xkcd.com/927/) 🙂, I think Metro offers a compelling feature set with interop hooks that make it easy to integrate with an existing codebase._

## Getting Started

Check out the [Quick Start](quickstart.md) guide to get up and running with Metro, including installation and common patterns like the api/impl binding pattern.

## Installation

Apply the gradle plugin.

```kotlin
plugins {
  id("dev.zacsweers.metro") version "x.y.z"
}
```

And that's it! The default configuration will add the multiplatform `runtime` artifact (which has annotations you can use) and wire it all automatically.

You can configure custom behaviors with APIs on the `metro` DSL extension.

```kotlin
metro {
  // Defines whether or not metro is enabled. Useful if you want to gate this behind a dynamic
  // build configuration.
  enabled = true // Default

  // Enable (extremely) verbose debug logging
  debug = false // Default

  // See the kdoc on MetroPluginExtension for full details
}
```

Snapshots of the development version are available in [Sonatype's `snapshots` repository][snapshots].

## Supported platforms

The compiler plugin itself supports all multiplatform project types. The first-party annotations artifact is also multiplatform and supports all common JVM, JS, and native targets.

Contribution hint generation is _not_ currently supported on native or Wasm targets until [KT-58886](https://youtrack.jetbrains.com/issue/KT-58886) is resolved. They do work within the same compilation though.

## Caveats

- Kotlin compiler plugins are not a stable API! Compiled outputs from this plugin _should_ be stable, but usage in newer versions of kotlinc are not guaranteed to be stable. That said, Metro will strive to make matching releases available quickly and test against Kotlin prereleases eagerly.

## Acknowledgements

Special thanks to the many people that helped advise and offer feedback on Metro's design and implementation.

- Brian Norman
- Ralf Wondratschek
- Jesse Wilson
- Dany Santiago
- Eva Tatarka
- Bridget Phillips
- Bryan Stern
- James Barr
- and many more!

License
-------

    Copyright (C) 2025 Zac Sweers

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

[snapshots]: https://central.sonatype.com/repository/maven-snapshots/
