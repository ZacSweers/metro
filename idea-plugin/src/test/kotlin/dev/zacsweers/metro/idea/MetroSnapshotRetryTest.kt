// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.index.FileShard
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.index.snapshot.IndexInputs
import dev.zacsweers.metro.idea.index.snapshot.IndexOptionsFingerprint
import dev.zacsweers.metro.idea.index.snapshot.ResolutionInputCapture
import dev.zacsweers.metro.idea.index.snapshot.ResolutionSnapshotBuilder
import dev.zacsweers.metro.idea.index.snapshot.ResolutionSnapshotTarget
import dev.zacsweers.metro.idea.index.snapshot.SnapshotKey
import dev.zacsweers.metro.idea.index.snapshot.SourceFileShardCache
import dev.zacsweers.metro.idea.index.snapshot.SourceSnapshotChanges
import dev.zacsweers.metro.idea.model.IndexGenerationToken
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtFile

/** Exercises interrupted snapshot preparation without relying on background scheduling. */
class MetroSnapshotRetryTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
  }

  fun testForcedShardIsReusedAfterCancellation() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val reads = mutableListOf<FileShard>()
    var cancel = true
    val builder = builder { _, shard ->
      reads += shard
      if (cancel) {
        cancel = false
        throw ProcessCanceledException()
      }
    }
    try {
      prepare(builder, file)
      fail("Expected cancellation after reading a shard")
    } catch (_: ProcessCanceledException) {
      // The retry keeps the completed shard even though its candidate was never published.
    }
    prepare(builder, file)
    assertEquals(2, reads.size)
    assertSame(reads[0], reads[1])
  }

  fun testNewForcedRevisionRebuildsTheShard() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val reads = mutableListOf<FileShard>()
    val builder = builder { _, shard -> reads += shard }
    prepare(builder, file, revision = 1)
    prepare(builder, file, revision = 2)
    val events = mutableListOf<IndexBuildProgress>()
    prepare(builder, file, revision = 2) { events += it }
    assertEquals(3, reads.size)
    assertNotSame(reads[0], reads[1])
    assertSame(reads[1], reads[2])
    val completed = events.last { it.phase == IndexBuildPhase.ANALYZING_DECLARATIONS }
    assertEquals(1, completed.reused)
    assertEquals(0, completed.rebuilt)
  }

  fun testUnannotatedShardIsReused() {
    val file = myFixture.configureMetroFile("class Unrelated")
    val cache = SourceFileShardCache()
    allowAnalysisOnEdt {
      val first = cache.read(file, null)
      assertTrue(first.shard.bindings.isEmpty())
      assertTrue(first.rebuilt)
      assertFalse(cache.read(file, null).rebuilt)
    }
  }

  fun testCompletedClassResolutionIsReusedAfterCancellation() {
    val file =
      myFixture.configureMetroFile(
        "@Inject class Example; @DependencyGraph interface AppGraph { val example: Example }"
      )
    val builder = builder()
    val events = mutableListOf<IndexBuildProgress>()
    var cancel = true
    try {
      prepare(builder, file) { progress ->
        events += progress
        if (cancel && progress.phase == IndexBuildPhase.BUILDING_GRAPH_INDEX) {
          cancel = false
          throw ProcessCanceledException()
        }
      }
      fail("Expected cancellation after resolving source classes")
    } catch (_: ProcessCanceledException) {
      // A later phase can be canceled after source class resolution has completed.
    }
    val prepared = prepare(builder, file) { events += it }
    assertNotNull(prepared.source?.librarySummary)
    assertEquals(1, events.count { it.phase == IndexBuildPhase.RESOLVING_CLASS_BINDINGS })
  }

  fun testCompletedLibraryResolutionIsReusedAfterCancellation() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val builder = builder()
    val events = mutableListOf<IndexBuildProgress>()
    try {
      prepare(builder, file, resolveFromLibraries = true) { progress ->
        events += progress
        if (progress.phase == IndexBuildPhase.BUILDING_GRAPH_INDEX) throw ProcessCanceledException()
      }
      fail("Expected cancellation after reading library metadata")
    } catch (_: ProcessCanceledException) {
      // The completed source summary also retains the ownership key for the library cache.
    }
    prepare(builder, file, resolveFromLibraries = true) { events += it }
    assertEquals(1, events.count { it.phase == IndexBuildPhase.READING_DEPENDENCY_METADATA })
    assertEquals(1, events.count { it.phase == IndexBuildPhase.RESOLVING_CLASS_BINDINGS })
  }

  fun testSourceEditInvalidatesCompletedClassResolution() {
    val file =
      myFixture.configureMetroFile(
        "@Inject class Example; @DependencyGraph interface AppGraph { val example: Example }"
      )
    val builder = builder()
    val first = prepare(builder, file)
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
    WriteCommandAction.runWriteCommandAction(project) {
      val offset = document.text.indexOf("@Inject class Example")
      document.deleteString(offset, offset + "@Inject ".length)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val second = prepare(builder, file)
    assertNotSame(first.source!!.librarySummary, second.source!!.librarySummary)
    assertTrue(second.source!!.librarySummary!!.sourceClasses.addedBindings.isEmpty())
  }

  fun testClassDependencyEditInvalidatesCompletedClassResolution() {
    val registry =
      myFixture.addFileToProject("test/Registry.kt", "package test; object Registry") as KtFile
    val graph =
      myFixture.configureMetroFile("@DependencyGraph interface AppGraph { val registry: Registry }")
    val builder = builder()
    val first = prepare(builder, graph)
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(registry))
    WriteCommandAction.runWriteCommandAction(project) {
      document.setText("package test; class Registry")
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val second = prepare(builder, graph)
    assertNotSame(first.source!!.librarySummary, second.source!!.librarySummary)
    assertTrue(second.source!!.librarySummary!!.sourceClasses.addedBindings.isEmpty())
  }

  private fun builder(onShardRead: (KtFile, FileShard) -> Unit = { _, _ -> }) =
    ResolutionSnapshotBuilder(
      project,
      onShardRead,
      ResolutionInputCapture(project) { _, _ -> }::capture,
    )

  /** Each call represents a retry of the same forced source invalidation. */
  private fun prepare(
    builder: ResolutionSnapshotBuilder,
    file: KtFile,
    revision: Long = 0,
    resolveFromLibraries: Boolean = false,
    publish: (IndexBuildProgress) -> Unit = {},
  ) = allowAnalysisOnEdt {
    builder.prepare(
      previous = null,
      inputs = IndexInputs(0, 0),
      targets =
        listOf(
          ResolutionSnapshotTarget(
            SnapshotKey(
              IndexOptionsFingerprint(file.metroIdeState().options),
              resolveFromLibraries,
            ),
            listOf(module),
          )
        ),
      pending =
        SourceSnapshotChanges(
          emptySet(),
          setOf(file.virtualFile),
          emptySet(),
          true,
          invalidationRevision = revision,
        ),
      coldSweep = true,
      progress = IndexBuildProgressReporter(publish),
      generationToken = IndexGenerationToken.create(),
      checkCurrent = {},
    )
  }
}
