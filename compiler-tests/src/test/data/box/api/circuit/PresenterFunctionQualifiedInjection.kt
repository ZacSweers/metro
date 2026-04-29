// ENABLE_CIRCUIT
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

import androidx.compose.runtime.Composable
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.screen.Screen

data object HomeScreen : Screen

data class HomeState(val greeting: String) : CircuitUiState

@Qualifier annotation class Greeting

@ContributesTo(AppScope::class)
interface GreetingModule {
  @Provides @Greeting fun provideGreeting(): String = "Hello from qualified!"
}

@CircuitInject(HomeScreen::class, AppScope::class)
@Inject
@Composable
fun HomePresenter(@Greeting greeting: String, navigator: Navigator): HomeState {
  return HomeState(greeting = greeting)
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val presenterFactories: Set<Presenter.Factory>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val factories = graph.presenterFactories
  if (factories.isEmpty()) return "FAIL: no factories"
  return "OK"
}
