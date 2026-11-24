---
hide:
  - navigation
---

# 🚇 Metro

# Introduction

Metro is a compile-time dependency injection framework for Kotlin. Its compiler plugin combines patterns from [Dagger](https://github.com/google/dagger), [Anvil](https://github.com/square/anvil), and [kotlin-inject](https://github.com/evant/kotlin-inject).

### Why another DI framework?

For a while I felt like we had all the pieces for a Kotlin DI library, just spread across a few different tools. I wanted Dagger’s graph validation and Anvil’s aggregation with an API that felt as natural in Kotlin as kotlin-inject. Building Metro as a Kotlin compiler plugin also lets it add code to existing classes. For example, a generated factory can use a default argument that references a private member.

!!! note
    _I know the [XKCD comic](https://xkcd.com/927/) 🙂. Metro's interop hooks are there so you can introduce it into an existing codebase and migrate a piece at a time._

## Getting Started

Start with the [Quick Start](quickstart.md) guide for installation and common patterns, including binding an implementation to its interface.

## Installation

Apply the Gradle plugin:

```kotlin
plugins {
  id("dev.zacsweers.metro") version "x.y.z"
}
```

And that’s it! The plugin adds the multiplatform `runtime` artifact, which contains Metro’s annotations, and configures the compiler plugin.

Use the `metro` DSL to configure Metro:

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

The compiler plugin supports Kotlin Multiplatform projects. The runtime annotations support common JVM, JS, and Native targets.

On Native and Wasm, contribution hints across compilations require Kotlin `2.3.20-Beta1` or later. Contributions within the same compilation also work on earlier versions.

## Caveats

- Kotlin compiler plugin APIs are unstable. Generated outputs should remain stable, but a newer Kotlin compiler may require a Metro update. Metro tests Kotlin prereleases and aims to support new releases promptly.

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

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

[snapshots]: https://central.sonatype.com/repository/maven-snapshots/
