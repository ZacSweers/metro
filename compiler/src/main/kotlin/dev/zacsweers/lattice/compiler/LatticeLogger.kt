package dev.zacsweers.lattice.compiler

public interface LatticeLogger {
  public val type: Type

  public fun indent(): LatticeLogger

  public fun unindent(): LatticeLogger

  public fun log(message: String) {
    log { message }
  }

  public fun log(message: () -> String)

  public enum class Type {
    None,
    GraphNodeConstruction,
    BindingGraphConstruction,
    CycleDetection,
    GraphImplCodeGen,
  }

  public companion object {
    public operator fun invoke(type: Type, output: (String) -> Unit): LatticeLogger {
      return LatticeLoggerImpl(type, output)
    }

    public val NONE: LatticeLogger = LatticeLoggerImpl(Type.None) {}
  }
}

internal class LatticeLoggerImpl(
  override val type: LatticeLogger.Type,
  val output: (String) -> Unit,
) : LatticeLogger {
  private var indent = 0

  override fun indent() = apply { indent++ }

  override fun unindent() = apply {
    indent--
    if (indent < 0) error("Unindented too much!")
  }

  override fun log(message: () -> String) {
    output("  ".repeat(indent) + message())
  }
}
