Weather App
===========

A simple sample weather app.

To run the JVM CLI, run

```bash
$ ./gradlew -p samples :weather-app:jvmRun -DmainClass=dev.zacsweers.metro.sample.weather.MainKt --quiet
```

To change the supplied location, add `--args="--location New York"` to the end, replacing "New York" with your own
query.