package dev.zacsweers.lattice.sample

import dagger.BindsInstance
import dagger.Component

@Component
interface StringComponent {
  val message: String

  @Component.Factory
  interface Factory {
    fun create(@BindsInstance message: String): StringComponent
  }
}
