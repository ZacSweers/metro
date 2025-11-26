# 🚇 Metro

A compile-time dependency injection framework for Kotlin Multiplatform, powered by a Kotlin compiler plugin.

[![Maven Central](https://img.shields.io/maven-central/v/dev.zacsweers.metro/runtime.svg)](https://github.com/ZacSweers/metro/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20--2.3.0--RC-blue.svg?logo=kotlin)](docs/compatibility.md)
[![Build Status](https://github.com/ZacSweers/metro/actions/workflows/ci.yml/badge.svg)](https://github.com/ZacSweers/metro/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

---

## What is Metro?

Metro is a compile-time dependency injection framework that combines the best of [Dagger](https://github.com/google/dagger), [Anvil](https://github.com/square/anvil), and [kotlin-inject](https://github.com/evant/kotlin-inject) into one cohesive solution.

**Key Features:**

- ✅ **Compile-time validation** – Catch dependency graph errors during compilation, not at runtime
- 🧩 **FIR/IR code generation** – No KAPT or KSP required, just a Kotlin compiler plugin
- 🎯 **Kotlin-first API** – Inspired by kotlin-inject with top-level function injection and optional dependencies
- 🗡️ **Dagger-esque runtime** – Lean generated code with familiar patterns
- ⚒️ **Anvil-style aggregation** – `@ContributesTo`, `@ContributesBinding`, and more
- 🌐 **Multiplatform** – Supports JVM, JS, WASM, and Native targets
- 💡 **Helpful diagnostics** – Detailed error messages with actionable suggestions
- 🔗 **Advanced interop** – Migrate incrementally from Dagger, kotlin-inject, or Guice

---

## Quick Start

**1. Apply the Gradle plugin:**

```kotlin
plugins {
  id("dev.zacsweers.metro") version "<version>"
}
```

**2. Define a dependency graph:**

```kotlin
@DependencyGraph
interface AppGraph {
  val repository: UserRepository

  @Provides
  fun provideApi(): Api = ApiImpl()
}

@Inject
class UserRepository(private val api: Api)
```

**3. Create and use the graph:**

```kotlin
val graph = createGraph<AppGraph>()
val repository = graph.repository
```

---

## Documentation

📚 **[zacsweers.github.io/metro](https://zacsweers.github.io/metro/latest/)**

| Topic                                                                            |                                                |
|----------------------------------------------------------------------------------|------------------------------------------------|
| [Installation](https://zacsweers.github.io/metro/latest/installation/)           | Setup and configuration                        |
| [Dependency Graphs](https://zacsweers.github.io/metro/latest/dependency-graphs/) | Define and create graphs                       |
| [Provides](https://zacsweers.github.io/metro/latest/provides/)                   | Provider functions and properties              |
| [Injection Types](https://zacsweers.github.io/metro/latest/injection-types/)     | Constructor, assisted, and member injection    |
| [Scopes](https://zacsweers.github.io/metro/latest/scopes/)                       | Scoping and lifecycle management               |
| [Aggregation](https://zacsweers.github.io/metro/latest/aggregation/)             | Anvil-style contributions across modules       |
| [Interop](https://zacsweers.github.io/metro/latest/interop/)                     | Dagger, kotlin-inject, and Guice compatibility |
| [Multiplatform](https://zacsweers.github.io/metro/latest/multiplatform/)         | Cross-platform support                         |
| [Performance](https://zacsweers.github.io/metro/latest/performance/)             | Build and runtime performance                  |
| [Compatibility](https://zacsweers.github.io/metro/latest/compatibility/)         | Supported Kotlin versions                      |
| [FAQ](https://zacsweers.github.io/metro/latest/faq/)                             | Frequently asked questions                     |
| [API Docs](https://zacsweers.github.io/metro/latest/api/)                        | Generated KDocs                                |

---

## Supported Platforms

Metro supports JVM, JS, and Native targets. The compiler plugin works with all Kotlin Multiplatform project types.

See the [compatibility docs](https://zacsweers.github.io/metro/latest/compatibility/) for supported Kotlin versions.

---

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