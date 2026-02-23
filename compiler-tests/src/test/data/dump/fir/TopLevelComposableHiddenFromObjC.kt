// ENABLE_TOP_LEVEL_FUNCTION_INJECTION

// FILE: Composable.kt
package androidx.compose.runtime

annotation class Composable

annotation class Stable

// FILE: main.kt
import androidx.compose.runtime.Composable

@Composable
@Inject
fun App(message: String) {
}
