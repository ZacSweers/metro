// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.tracing

import androidx.tracing.AbstractTraceDriver
import androidx.tracing.wire.TraceDriver as WireTraceDriver
import androidx.tracing.wire.TraceSink
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.ir.MetroIrPipeline
import java.io.Closeable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.EmptyCoroutineContext
import okio.buffer
import okio.sink
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension

/**
 * Tracks the FIR-side trace drivers for one Metro plugin registration so they can be finalized when
 * IR begins, and acts as the single factory for both FIR and IR trace drivers so every file
 * produced by one compilation shares a common [id] prefix.
 *
 * Each [FirSession][org.jetbrains.kotlin.fir.FirSession] gets its own driver/file, as does each
 * [IrGenerationExtension.generate] call. We don't try to unify FIR and IR into a single timeline;
 * KMP source sets and platform targets often produce multiple of each, and parallel compilations
 * would clobber a shared file anyway. Filenames look like `<id>-fir-<moduleName>.perfetto-trace` /
 * `<id>-ir-<moduleName>.perfetto-trace`.
 *
 * IR drivers are owned by the per-fragment DI graph and finalized by [MetroIrPipeline]'s
 * `traceDriver.use { ... }`. FIR has no equivalent close hook, so the IR pipeline calls [close] at
 * the start of its run to finalize any open FIR files.
 */
public class TraceContext(private val options: MetroOptions) : Closeable {

  /** Stable, lexicographically sortable identifier for this compilation's trace files. */
  public val id: String = generateTraceId()

  private val firDrivers = mutableListOf<AbstractTraceDriver>()
  private var firClosed = false

  /** Creates a new FIR driver for the given session/module. Null when tracing is disabled. */
  public fun newFirDriverOrNull(moduleName: String): AbstractTraceDriver? {
    if (firClosed) return null
    val driver = newDriverOrNull(phase = "fir", moduleName) ?: return null
    firDrivers.add(driver)
    return driver
  }

  /** Creates a new IR driver for the given module fragment. Null when tracing is disabled. */
  public fun newIrDriverOrNull(moduleName: String): AbstractTraceDriver? =
    newDriverOrNull(phase = "ir", moduleName)

  /** Closes all open FIR drivers. Idempotent; safe to call from each IR fragment. */
  override fun close() {
    if (firClosed) return
    firClosed = true
    firDrivers.forEach { it.close() }
    firDrivers.clear()
  }

  private fun newDriverOrNull(phase: String, moduleName: String): AbstractTraceDriver? {
    if (!options.traceEnabled) return null
    val tracePath = options.traceDir.value ?: return null
    // Dir is already ensured to exist by MetroOptions.traceDir; don't
    // delete here — that would clobber prior-iteration traces inside a
    // single Gradle daemon (and throws on a non-empty directory anyway).
    val file = tracePath.resolve("$id-$phase-${moduleName.sanitize()}.perfetto-trace").toFile()
    return WireTraceDriver(
      sink = TraceSink(sequenceId = 1, file.sink().buffer(), EmptyCoroutineContext),
      isEnabled = true,
    )
  }

  private companion object {
    val ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd-HHmmss")
    // Chars that are reserved on Windows or behave as path separators. Everything else (letters,
    // digits, dashes, underscores, dots, plus any other non-control unicode) is fine.
    val UNSAFE_FILENAME_CHARS = Regex("""[<>:"/\\|?*]""")

    /**
     * Single point to swap the ID generator. TODO replace with `Uuid.generateV7NonMonotonicAt(...)`
     * (KT stdlib) once we can rely on that API — gives us lexicographically sortable, time-prefixed
     * IDs without manual formatting.
     */
    fun generateTraceId(): String = LocalDateTime.now().format(ID_FORMAT)

    fun String.sanitize(): String =
      removePrefix("<").removeSuffix(">").replace(UNSAFE_FILENAME_CHARS, "_").ifBlank { "unknown" }
  }
}
