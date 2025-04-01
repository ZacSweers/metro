package dev.zacsweers.metro.sample.multimodule.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppIntegrationTest {
  @Test
  fun test() {
    val appGraph = createAppGraph()

    // Parent graph
    assertThat(appGraph.parentGraph.messageService.getMessage()).isEqualTo("Message from parent")
    assertThat(appGraph.parentGraph.numberService.getNumber()).isEqualTo(0)

    // Child graph
    // From the parent
    assertThat(appGraph.childGraph.messageService.getMessage()).isEqualTo("Message from parent")
    assertThat(appGraph.childGraph.numberService.getNumber()).isEqualTo(0)
    assertThat(appGraph.childGraph.itemService.getItems()).containsExactly("Item 1", "Item 2", "Item 3")
    assertThat(appGraph.childGraph.combinedMessage).isEqualTo("Message from parent - Number: 0 - Items: 3")

    // Aggregated
    assertThat(appGraph.aggregationSummary.trim()).isEqualTo(
      """
        Aggregation Summary:
        - Default message: Message from parent
        - Contributed message service: Message from contributed service
        - Contributed message: Message from contributor
        - Item services count: 1
        - Items from all services: [Contributed Item 1, Contributed Item 2]
        - Map services count: 1
        - Map services keys: [contributor]
      """.trimIndent()
    )
  }
}
