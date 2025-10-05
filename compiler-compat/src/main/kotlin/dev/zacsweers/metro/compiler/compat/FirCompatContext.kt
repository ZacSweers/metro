package dev.zacsweers.metro.compiler.compat

import java.util.ServiceLoader
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol

public interface FirCompatContext {
  public companion object {
    private fun loadFactories(): Sequence<Factory> {
      return ServiceLoader.load(Factory::class.java).asSequence()
    }

    /**
     * Load [factories][Factory] and pick the highest compatible version (by [Factory.minVersion])
     */
    public fun resolveFactory(
      factories: Sequence<Factory> = loadFactories(),
      testVersion: String? = null,
    ): Factory {
      val targetFactory =
        factories
          .mapNotNull { factory ->
            // Filter out any factories that can't compute the Kotlin version, as
            // they're _definitely_ not compatible
            try {
              FactoryData(factory.currentVersion, factory)
            } catch (_: Throwable) {
              null
            }
          }
          .filter { (version, factory) -> (testVersion ?: version) >= factory.minVersion }
          .maxByOrNull { (_, factory) -> factory.minVersion }
          ?.factory ?: error("Unrecognized Kotlin version!")
      return targetFactory
    }

    public fun create(): FirCompatContext = resolveFactory().create()
  }

  public interface Factory {
    public val minVersion: String
      get() = currentVersion

    /** Attempts to get the current AGP version or throws and exception if it cannot. */
    public val currentVersion: String

    public fun create(): FirCompatContext
  }

  /**
   * Returns the ClassLikeDeclaration where the Fir object has been defined or null if no proper
   * declaration has been found. The containing symbol is resolved using the declaration-site
   * session. For example:
   * ```kotlin
   * expect class MyClass {
   *     fun test() // (1)
   * }
   *
   * actual class MyClass {
   *     actual fun test() {} // (2)
   * }
   * ```
   *
   * Calling [getContainingClassSymbol] for the symbol of `(1)` will return `expect class MyClass`,
   * but calling it for `(2)` will give `actual class MyClass`.
   */
  // Deleted in Kotlin 2.3.0
  public fun FirBasedSymbol<*>.getContainingClassSymbol(): FirClassLikeSymbol<*>?

  /**
   * Returns the containing class or file if the callable is top-level. The containing symbol is
   * resolved using the declaration-site session.
   */
  // Deleted in Kotlin 2.3.0
  public fun FirCallableSymbol<*>.getContainingSymbol(session: FirSession): FirBasedSymbol<*>?

  /** The containing symbol is resolved using the declaration-site session. */
  // Deleted in Kotlin 2.3.0
  public fun FirDeclaration.getContainingClassSymbol(): FirClassLikeSymbol<*>?
}

private data class FactoryData(val version: String, val factory: FirCompatContext.Factory)
