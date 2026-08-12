# Metro external compiler plugin fixture

This fixture exercises Metro as a compiler plugin that is unknown when the Kotlin compiler native image is built. It uses Metro's normal shaded compiler JAR, including all compatibility providers, service descriptors, reflection paths, and embedded dependencies.

Build the bundle from the Metro repository root:

```shell
./gradlew :compiler:cremaFixture
unzip compiler/build/distributions/metro-crema-fixture.zip -d /tmp/metro-crema-fixture
```

The bundle contains `metro-compiler.jar`, `metro-runtime.jar`, `Smoke.kt`, `fixture.properties`, and `run-smoke-test.sh`.

## Compatibility baseline

The default compiler version in `fixture.properties` is the newest Kotlin compiler version represented by this fixture. The file also records its exact Kotlin tag and commit. Run the fixture against the JVM compiler built from that source baseline before testing the native executable:

```shell
/tmp/metro-crema-fixture/run-smoke-test.sh /path/to/kotlin/dist/kotlinc/bin/kotlinc
```

The command must print `OK`. A failure here is a Metro/Kotlin compiler compatibility problem rather than a Native Image runtime-loading problem.

Do not use this bundle with a later Kotlin revision until Metro has a compatibility provider for that compiler ABI and the same JAR passes the JVM baseline. After adding that provider, pass its supported compiler version explicitly:

```shell
/tmp/metro-crema-fixture/run-smoke-test.sh /path/to/kotlin/dist/kotlinc/bin/kotlinc <supported-compiler-version>
```

## Native Image test

Build the Kotlin compiler native image without `metro-compiler.jar` on its build classpath and without using Metro to generate reachability metadata. Pass Metro only when invoking the completed native executable:

```shell
/tmp/metro-crema-fixture/run-smoke-test.sh /path/to/kotlin/prepare/compiler-native-image/build/dist/bin/kotlinc-native-image.sh
```

Use the distribution launcher rather than the raw native binary. The launcher supplies the required `java.home` and `kotlin.home` system properties.

Use the same Metro JAR, runtime JAR, compiler version, source file, and compiler options in the JVM and native runs. If the JVM run prints `OK` and the native run fails, the difference is in runtime plugin loading or in the compiler API and reflection metadata preserved in the native image.

The source is also suitable for Kotlin's box-test harness. Its `box()` function returns `OK` only after Metro registers its FIR extensions, constructs the dependency graph, runs IR generation, and replaces the runtime `createGraph()` stub.
