// ENABLE_CIRCUIT
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MIN_COMPILER_VERSION: 2.3.20

// MODULE: screens

import com.slack.circuit.subcircuit.SubCircuitOuterEvent
import com.slack.circuit.subcircuit.SubCircuitUiState
import com.slack.circuit.subcircuit.SubScreen

sealed interface TestOuterEvent : SubCircuitOuterEvent

data class TestState(val label: String) : SubCircuitUiState

data object DirectPresenterScreen : SubScreen<TestOuterEvent>

data class AssistedPresenterScreen(val id: String) : SubScreen<TestOuterEvent>

data class DirectUiScreen(val id: String) : SubScreen<TestOuterEvent>

data object ObjectUiScreen : SubScreen<TestOuterEvent>

data class StatefulUiScreen(val id: String) : SubScreen<TestOuterEvent>

data object StatelessUiScreen : SubScreen<TestOuterEvent>

data object UnknownScreen : SubScreen<TestOuterEvent>

class UiDependency

// MODULE: presenters(screens)

import androidx.compose.runtime.Composable
import com.slack.circuit.subcircuit.SubCircuitInject
import com.slack.circuit.subcircuit.SubPresenter

interface DirectPresenterContract : SubPresenter<TestOuterEvent, TestState>

@Inject
@SubCircuitInject(DirectPresenterScreen::class, AppScope::class)
class DirectPresenter : DirectPresenterContract {
  @Composable
  override fun present(outerEventSink: (TestOuterEvent) -> Unit): TestState {
    return TestState("direct")
  }
}

@AssistedInject
class AssistedPresenter(
  @Assisted val target: AssistedPresenterScreen,
) : SubPresenter<TestOuterEvent, TestState> {
  @SubCircuitInject(AssistedPresenterScreen::class, AppScope::class)
  @AssistedFactory
  fun interface Factory {
    fun build(target: AssistedPresenterScreen): AssistedPresenter
  }

  @Composable
  override fun present(outerEventSink: (TestOuterEvent) -> Unit): TestState {
    return TestState(target.id)
  }
}

// MODULE: uis(screens)

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.slack.circuit.subcircuit.SubCircuitInject
import com.slack.circuit.subcircuit.SubUi

@Inject
@SubCircuitInject(DirectUiScreen::class, AppScope::class)
class DirectUi : SubUi<TestState> {
  @Composable
  override fun Content(state: TestState, modifier: Modifier) = Unit
}

@SubCircuitInject(ObjectUiScreen::class, AppScope::class)
data object ObjectUi : SubUi<TestState> {
  @Composable
  override fun Content(state: TestState, modifier: Modifier) = Unit
}

@SubCircuitInject(StatefulUiScreen::class, AppScope::class)
@Composable
fun StatefulUi(model: TestState, mod: Modifier, dependency: UiDependency) = Unit

@SubCircuitInject(StatelessUiScreen::class, AppScope::class)
@Composable
fun StatelessUi(mod: Modifier) = Unit

// MODULE: main(presenters, uis, screens)

import com.slack.circuit.subcircuit.SubPresenterFactory
import com.slack.circuit.subcircuit.SubUiFactory

@DependencyGraph(AppScope::class)
abstract class AppGraph {
  abstract val presenterFactories: Set<SubPresenterFactory>
  abstract val uiFactories: Set<SubUiFactory>

  var uiDependencyCreations = 0

  @Provides
  fun provideUiDependency(): UiDependency {
    uiDependencyCreations++
    return UiDependency()
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val presenterFactories = graph.presenterFactories
  val uiFactories = graph.uiFactories

  if (presenterFactories.size != 2) {
    return "FAIL: expected 2 presenter factories but got ${presenterFactories.size}"
  }
  if (uiFactories.size != 4) {
    return "FAIL: expected 4 UI factories but got ${uiFactories.size}"
  }

  if (presenterFactories.any { it.create(UnknownScreen) != null }) {
    return "FAIL: presenter factory matched an unknown screen"
  }
  if (uiFactories.any { it.create(UnknownScreen) != null }) {
    return "FAIL: UI factory matched an unknown screen"
  }
  if (graph.uiDependencyCreations != 0) {
    return "FAIL: injected UI dependency was created for a nonmatching screen"
  }

  val directPresenters = presenterFactories.mapNotNull { it.create(DirectPresenterScreen) }
  if (directPresenters.singleOrNull() !is DirectPresenter) {
    return "FAIL: direct presenter was not created"
  }

  val assistedScreen = AssistedPresenterScreen("assisted")
  val assistedPresenters = presenterFactories.mapNotNull { it.create(assistedScreen) }
  val assistedPresenter = assistedPresenters.singleOrNull() as? AssistedPresenter
    ?: return "FAIL: assisted presenter was not created"
  if (assistedPresenter.target != assistedScreen) {
    return "FAIL: assisted screen was not forwarded"
  }

  val directUis = uiFactories.mapNotNull { it.create(DirectUiScreen("direct")) }
  if (directUis.singleOrNull() !is DirectUi) {
    return "FAIL: direct UI was not created"
  }

  val objectUis = uiFactories.mapNotNull { it.create(ObjectUiScreen) }
  if (objectUis.singleOrNull() !== ObjectUi) {
    return "FAIL: object UI was not created"
  }

  val statefulUis = uiFactories.mapNotNull { it.create(StatefulUiScreen("stateful")) }
  if (statefulUis.size != 1) return "FAIL: stateful UI was not created"
  if (graph.uiDependencyCreations != 1) {
    return "FAIL: expected one injected UI dependency but got ${graph.uiDependencyCreations}"
  }

  uiFactories.mapNotNull { it.create(StatefulUiScreen("stateful-again")) }
  if (graph.uiDependencyCreations != 2) {
    return "FAIL: injected UI dependency was not resolved once per create call"
  }

  val statelessUis = uiFactories.mapNotNull { it.create(StatelessUiScreen) }
  if (statelessUis.size != 1) return "FAIL: stateless UI was not created"

  return "OK"
}
