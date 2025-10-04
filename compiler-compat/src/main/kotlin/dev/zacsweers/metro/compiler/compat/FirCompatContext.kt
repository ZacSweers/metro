package dev.zacsweers.metro.compiler.compat

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol

public interface FirCompatContext {

  public interface Factory {
    public val kotlinVersion: String

    public fun create(): FirCompatContext
  }

  /**
   * Returns the ClassLikeDeclaration where the Fir object has been defined
   * or null if no proper declaration has been found.
   * The containing symbol is resolved using the declaration-site session.
   * For example:
   *
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
   * Calling [getContainingClassSymbol] for the symbol of `(1)` will return
   * `expect class MyClass`, but calling it for `(2)` will give `actual class MyClass`.
   */
  // Deleted in Kotlin 2.3.0
  public fun FirBasedSymbol<*>.getContainingClassSymbol(): FirClassLikeSymbol<*>?

  /**
   * Returns the containing class or file if the callable is top-level.
   * The containing symbol is resolved using the declaration-site session.
   */
  // Deleted in Kotlin 2.3.0
  public fun FirCallableSymbol<*>.getContainingSymbol(session: FirSession): FirBasedSymbol<*>?

  /**
   * The containing symbol is resolved using the declaration-site session.
   */
  // Deleted in Kotlin 2.3.0
  public fun FirDeclaration.getContainingClassSymbol(): FirClassLikeSymbol<*>?


}
