# Circuit Sample

This is a sample that demonstrates using Metro with [Circuit](https://github.com/slackhq/circuit).

## Features

- Compose
- Multiplatform
- Uses Metro's native Circuit code gen + demonstrates multiplatform integration with it
- Top-level composable function injection (requires enabling [IDE support](https://zacsweers.github.io/metro/latest/installation/#ide-support))

## Entry points

**JVM**

```kotlin
./gradlew -p samples :circuit-app:jvmRun
```

**Wasm**

```kotlin
./gradlew -p samples :circuit-app:wasmJsBrowserDevelopmentRun --continuous
```
