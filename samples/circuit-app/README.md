# Circuit Sample

This is a sample that demonstrates using Metro with [Circuit](https://github.com/slackhq/circuit).

## Features

- Compose
- Multiplatform
- Uses Circuit (KSP) code gen + demonstrates multiplatform integration with it

Note that KSP's support for generating common code is slightly broken, so this uses the workaround described [here](https://github.com/google/ksp/issues/567#issuecomment-2609469736).

## Entry points

**JVM**

```kotlin
./gradlew -p samples :circuit-app:jvmRun
```

TODO others