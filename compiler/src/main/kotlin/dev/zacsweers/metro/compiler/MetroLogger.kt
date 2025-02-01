// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

public interface MetroLogger {
  public val type: Type

  public fun indent(): MetroLogger

  public fun unindent(): MetroLogger

  public fun log(message: String) {
    log { message }
  }

  public fun log(message: () -> String)

  public enum class Type {
    None,
    FirSupertypeGeneration,
    FirDeclarationGeneration,
    GraphNodeConstruction,
    BindingGraphConstruction,
    CycleDetection,
    GraphImplCodeGen,
    GeneratedFactories,
  }

  public companion object {
    public operator fun invoke(
      type: Type,
      output: (String) -> Unit,
      tag: String? = null,
    ): MetroLogger {
      return MetroLoggerImpl(type, output, tag)
    }

    public val NONE: MetroLogger = MetroLoggerImpl(Type.None, {})
  }
}

internal class MetroLoggerImpl(
  override val type: MetroLogger.Type,
  val output: (String) -> Unit,
  private val tag: String? = null,
) : MetroLogger {
  private var indent = 0

  override fun indent() = apply { indent++ }

  override fun unindent() = apply {
    indent--
    if (indent < 0) error("Unindented too much!")
  }

  override fun log(message: () -> String) {
    val fullMessage = buildString {
      tag?.let { append("[$it] ") }
      append("  ".repeat(indent))
      append(message())
    }
    output(fullMessage)
  }
}
