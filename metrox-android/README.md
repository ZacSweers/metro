# MetroX Android

Core Android support for Metro. This artifact specifically focuses on integration with `AppComponentFactory` (which requires API 28+).

## Usage

For simple cases, all you need to do is

1. Depend on this artifact

    [![Maven Central](https://img.shields.io/maven-central/v/dev.zacsweers.metro/metrox-android.svg)](https://central.sonatype.com/artifact/dev.zacsweers.metro/metrox-android)
    ```kotlin
    dependencies {
      implementation("dev.zacsweers.metrox:metrox-android:x.y.z")
    }
    ```
2. Make your `AppGraph` (or equivalent) implement `MetroAppComponentProviders`.
    ```kotlin
    @DependencyGraph(AppScope::class)
    interface AppGraph : MetroAppComponentProviders
    ```
3. Make your `Application` subclass implement `MetroApplication` and implement `appComponentProviders`.
    ```kotlin
    class MyApp : Application(), MetroApplication {
      private val appGraph by lazy { createGraph<AppGraph>() }
      override val appComponentProviders: MetroAppComponentProviders
        get() = appGraph
    }
    ```

## Advanced

If you have your own custom `AppComponentFactory`, you will need to exclude the MetroX implementation in your `AndroidManifest.xml` via `tools:replace` attribute.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest
  xmlns:android="http://schemas.android.com/apk/res/android"
  xmlns:tools="http://schemas.android.com/tools">

  <application
    ...
    android:appComponentFactory="your.custom.AppComponentFactory"
    tools:replace="android:appComponentFactory"
  >
  </application>
</manifest>
```

Then you can replicate what `MetroAppComponentFactory` does or subclass it in your custom factory.
