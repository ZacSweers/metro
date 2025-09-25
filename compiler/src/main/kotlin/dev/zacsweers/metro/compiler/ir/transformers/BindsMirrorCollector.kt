package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.MultibindsCallable
import dev.zacsweers.metro.compiler.ir.toBindsCallable
import dev.zacsweers.metro.compiler.ir.toMultibindsCallable
import dev.zacsweers.metro.compiler.reportCompilerBug
import kotlin.collections.plusAssign
import org.jetbrains.kotlin.ir.declarations.IrClass

/**
 * Simple helper class to collect binds callables and build a [BindsMirror].
 *
 * @property isInterop Indicates if this reflects a binds mirror of an interoped dagger module,
 *   which won't actually have a true mirror class and just use the original class instead.
 */
internal class BindsMirrorCollector(private val isInterop: Boolean) {
  private val bindsCallables = mutableSetOf<BindsCallable>()
  private val multibindsCallables = mutableSetOf<MultibindsCallable>()

  context(context: IrMetroContext)
  operator fun plusAssign(function: MetroSimpleFunction) {
    if (function.annotations.isBinds) {
      bindsCallables += function.toBindsCallable(isInterop)
    } else if (function.annotations.isMultibinds) {
      multibindsCallables += function.toMultibindsCallable(isInterop)
    } else {
      reportCompilerBug("Not a binds or multibinds")
    }
  }

  fun buildMirror(clazz: IrClass): BindsMirror {
    return BindsMirror(clazz, bindsCallables, multibindsCallables)
  }
}
