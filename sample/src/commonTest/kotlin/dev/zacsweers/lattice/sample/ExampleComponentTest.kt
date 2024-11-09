package dev.zacsweers.lattice.sample

import dev.zacsweers.lattice.createComponentFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class ExampleComponentTest {
  @Test
  fun simplePipeline() {
    val component = createComponentFactory<ExampleComponent.Factory>().create("Hello, world!")
    val example1 = component.example1()
    assertEquals("Hello, world!", example1.text)
  }
}
