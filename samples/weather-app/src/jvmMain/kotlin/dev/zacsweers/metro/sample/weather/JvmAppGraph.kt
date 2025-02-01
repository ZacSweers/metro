package dev.zacsweers.metro.sample.weather

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.SingleIn


@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface JvmAppGraph : AppGraph
