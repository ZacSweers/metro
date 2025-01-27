Metro
=====

A prototype dependency injection compiler plugin.

## Usage

TODO

## Installation

Apply the gradle plugin.

```gradle
plugins {
  id("dev.zacsweers.metro") version <version>
}
```

And that's it! The default configuration will add the multiplatform `runtime` artifact (which has annotations
you can use) and wire it all automatically.

You can configure custom behavior with properties on the `metro` extension.

```kotlin
metro {
  // Define whether or not this is enabled. Useful if you want to gate this behind a dynamic
  // build configuration.
  enabled = true // Default

  // Enable (extremely) verbose debug logging
  debug = false // Default
}
```

Snapshots of the development version are available in [Sonatype's `snapshots` repository][snapshots].

## Supported platforms

The compiler plugin itself supports all multiplatform project types. The first-party annotations artifact is also
multiplatform and supports all common JVM, JS, and native targets.

## Caveats

- Kotlin compiler plugins are not a stable API! Compiled outputs from this plugin _should_ be stable,
but usage in newer versions of kotlinc are not guaranteed to be stable.
- This is a prototype. See the issue tracker for incomplete/missing features.

License
-------

    Copyright (C) 2024 Zac Sweers

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 [snapshots]: https://oss.sonatype.org/content/repositories/snapshots/
