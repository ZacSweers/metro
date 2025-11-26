// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.tracing

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.TimeSource
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

internal interface Tracer {
  val tag: String
  val description: String

  fun start()

  fun stop()

  fun nested(description: String, tag: String = this.tag): Tracer

  companion object {
    val NONE: Tracer =
      object : Tracer {
        override val tag: String = ""
        override val description: String = ""

        override fun start() {
          // No op
        }

        override fun stop() {
          // No op
        }

        override fun nested(description: String, tag: String): Tracer {
          return NONE
        }
      }
  }
}

private class SimpleTracer(
  override val tag: String,
  override val description: String,
  private val level: Int,
  private val log: (String) -> Unit,
  private val onFinished: (String, String, Long) -> Unit,
) : Tracer {

  private var mark: ValueTimeMark? = null
  private inline val running
    get() = mark != null

  override fun start() {
    check(!running) { "Tracer already started" }
    val tagPrefix = if (level == 0) "[$tag] " else ""
    log("$tagPrefix${"  ".repeat(level)}▶ $description")
    mark = TimeSource.Monotonic.markNow()
  }

  override fun stop() {
    check(running) { "Tracer not started" }
    val elapsed = mark!!.elapsedNow()
    mark = null
    onFinished(tag, description, elapsed.inWholeMilliseconds)
    val tagPrefix = if (level == 0) "[$tag] " else ""
    log("$tagPrefix${"  ".repeat(level)}◀ $description (${elapsed.inWholeMilliseconds} ms)")
  }

  override fun nested(description: String, tag: String): Tracer =
    SimpleTracer(tag, description, level + 1, log, onFinished)
}

internal inline fun <T> Tracer.traceNested(
  description: String,
  tag: String = this.tag,
  block: (Tracer) -> T,
): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  return nested(description, tag).trace(block)
}

internal inline fun <T> Tracer.trace(block: (Tracer) -> T): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  start()
  try {
    return block(this)
  } finally {
    stop()
  }
}

internal fun tracer(
  tag: String,
  description: String,
  log: (String) -> Unit,
  onFinished: (String, String, Long) -> Unit,
): Tracer = SimpleTracer(tag, description, 0, log, onFinished)
