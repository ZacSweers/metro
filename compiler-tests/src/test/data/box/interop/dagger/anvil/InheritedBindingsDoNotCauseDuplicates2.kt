// ENABLE_DAGGER_INTEROP
// WITH_ANVIL
// Simplified test that bindings that are inherited by graph extensions don't get reported as duplicates

import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.MergeComponent
import dagger.Module

sealed interface TestScope

class TestValue

// Component with a nested module
@MergeComponent(TestScope::class)
interface TestComponent {
  val testValue: TestValue

  // Nested Dagger module with @ContributesTo
  @ContributesTo(TestScope::class)
  @Module
  object TestModule {
    @Provides
    fun provideTestValue(): TestValue {
      return TestValue()
    }
  }
}

fun box(): String {
  val component = createGraph<TestComponent>()
  assertNotNull(component.testValue)
  return "OK"
}
