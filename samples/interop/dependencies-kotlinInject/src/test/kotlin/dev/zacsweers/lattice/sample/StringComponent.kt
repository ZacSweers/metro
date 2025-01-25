package dev.zacsweers.lattice.sample

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Component
abstract class StringComponent(
  @get:Provides
  val message: String
)