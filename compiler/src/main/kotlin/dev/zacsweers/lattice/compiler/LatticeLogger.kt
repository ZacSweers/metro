/*
 * Copyright (C) 2025 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
    ): LatticeLogger {
      return LatticeLoggerImpl(type, output, tag)
    }

    public val NONE: LatticeLogger = LatticeLoggerImpl(Type.None, {})
  }
}

internal class LatticeLoggerImpl(
  override val type: LatticeLogger.Type,
  val output: (String) -> Unit,
  val tag: String? = null,
) : LatticeLogger {
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
