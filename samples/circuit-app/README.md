# Circuit Sample

This is a sample that demonstrates using Metro with [Circuit](https://github.com/slackhq/circuit).

## Features

- Compose
- Multiplatform
- Uses Circuit (KSP) code gen + demonstrates multiplatform integration with it
- Top-level composable function injection (requires enabling [IDE support](https://zacsweers.github.io/metro/latest/installation/#ide-support))

Note that KSP's support for generating common code is slightly broken, so this uses the workaround described [here](https://github.com/google/ksp/issues/567#issuecomment-2609469736).

## Entry points

**JVM**

```kotlin
./gradlew -p samples :circuit-app:jvmRun
```

**WASM**

```kotlin
./gradlew -p samples :circuit-app:wasmJsBrowserDevelopmentRun --continuous
```
