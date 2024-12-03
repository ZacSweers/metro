package dev.zacsweers.lattice.fir

import dev.zacsweers.lattice.appendIterableWith
import dev.zacsweers.lattice.unsafeLazy
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI

internal class LatticeFirAnnotation(val fir: FirAnnotationCall) {
  private val cachedHashKey by unsafeLazy { fir.computeAnnotationHash() }
  private val cachedToString by unsafeLazy {
    buildString {
      append('@')
      renderAsAnnotation(fir)
    }
  }

  // TODO
  //  fun isQualifier(session: FirSession) =
  // fir.resolvedType.toClassSymbol(session).isQualifierAnnotation
  //
  //  fun isScope(session: FirSession) = fir.resolvedType.toClassSymbol(session).isScopeAnnotation

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as LatticeFirAnnotation

    return cachedHashKey == other.cachedHashKey
  }

  override fun hashCode(): Int = cachedHashKey

  override fun toString() = cachedToString
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private fun StringBuilder.renderAsAnnotation(firAnnotation: FirAnnotationCall) {
  val annotationClassName = firAnnotation.resolvedType.classId?.asString() ?: "<unbound>"
  append(annotationClassName)

  // TODO type args not supported

  if (firAnnotation.arguments.isEmpty()) return

  appendIterableWith(
    0 until firAnnotation.arguments.size,
    separator = ", ",
    prefix = "(",
    postfix = ")",
  ) { index ->
    renderAsAnnotationArgument(firAnnotation.arguments[index])
  }
}

private fun StringBuilder.renderAsAnnotationArgument(argument: FirExpression) {
  println(argument)
  //  when (argument) {
  //    null -> append("<null>")
  //    is IrConstructorCall -> renderAsAnnotation(irElement)
  //    is IrConst -> {
  //      renderIrConstAsAnnotationArgument(irElement)
  //    }
  //    is IrVararg -> {
  //      appendIterableWith(irElement.elements, prefix = "[", postfix = "]", separator = ", ") {
  //        renderAsAnnotationArgument(it)
  //      }
  //    }
  //    else -> append("...")
  //  }
}

private fun StringBuilder.renderIrConstAsAnnotationArgument(const: IrConst) {
  val quotes =
    when (const.kind) {
      IrConstKind.String -> "\""
      IrConstKind.Char -> "'"
      else -> ""
    }
  append(quotes)
  append(const.value.toString())
  append(quotes)
}
