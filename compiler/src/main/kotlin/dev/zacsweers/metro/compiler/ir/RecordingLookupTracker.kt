package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.Position
import org.jetbrains.kotlin.incremental.components.ScopeKind
import org.jetbrains.kotlin.konan.file.File

internal class RecordingLookupTracker(
  private val context: IrMetroContext,
  private val delegate: LookupTracker,
) : LookupTracker by delegate {
  override fun record(
    filePath: String,
    position: Position,
    scopeFqName: String,
    scopeKind: ScopeKind,
    name: String,
  ) {
    delegate.record(filePath, position, scopeFqName, scopeKind, name)
    context.logLookup(
      filePath,
      position,
      scopeFqName,
      scopeKind,
      name,
    )
  }
}
