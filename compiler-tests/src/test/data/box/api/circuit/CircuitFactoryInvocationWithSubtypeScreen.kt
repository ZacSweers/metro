// ENABLE_CIRCUIT
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

// Regression test for https://github.com/ZacSweers/metro/issues/2227
// When a generated Circuit Presenter.Factory dispatches `screen: Screen` to an
// underlying assisted factory or composable expecting the concrete subtype
// `CounterScreen`, Metro must emit an implicit cast. JVM/JS silently tolerate
// the missing cast, wasm rejects the call_ref with a precise-type mismatch.

import androidx.compose.runtime.Composable
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.screen.Screen

data object CounterScreen : Screen

data class CounterState(val count: Int) : CircuitUiState

@AssistedInject
class CounterPresenter(@Assisted private val screen: CounterScreen) : Presenter<CounterState> {
  @CircuitInject(CounterScreen::class, AppScope::class)
  @AssistedFactory
  fun interface Factory {
    fun create(screen: CounterScreen): CounterPresenter
  }

  @Composable override fun present(): CounterState = CounterState(count = 1)
}

data object OtherScreen : Screen

data class OtherState(val count: Int) : CircuitUiState

@CircuitInject(OtherScreen::class, AppScope::class)
@Composable
fun OtherPresenter(screen: OtherScreen): OtherState = OtherState(count = 2)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val presenterFactories: Set<Presenter.Factory>
}

fun box(): String {
  val factories = createGraph<AppGraph>().presenterFactories
  if (factories.isEmpty()) return "FAIL: no factories"

  // Scenario 1: @AssistedInject + @AssistedFactory — Circuit's outer create(screen: Screen, ...)
  // must cast to CounterScreen before delegating to the assisted factory.
  val counterPresenter =
    factories.firstNotNullOfOrNull { it.create(CounterScreen, Navigator.NoOp, CircuitContext.EMPTY) }
      ?: return "FAIL: no presenter for CounterScreen"
  if (counterPresenter !is CounterPresenter) return "FAIL: wrong presenter type"

  // Scenario 2: @Inject @Composable function — the generated presenterOf{} lambda must cast
  // the captured `screen: Screen` param to OtherScreen before invoking the original function.
  factories.firstNotNullOfOrNull { it.create(OtherScreen, Navigator.NoOp, CircuitContext.EMPTY) }
    ?: return "FAIL: no presenter for OtherScreen"

  return "OK"
}
