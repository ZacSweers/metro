// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoWriter
import java.nio.file.Path
import okio.Buffer
import okio.BufferedSink
import okio.ByteString
import okio.appendingSink
import okio.buffer

/**
 * A completed wall-time interval, or an instant when [finished] is null. Contains no IDE objects.
 */
internal data class IdeTraceInterval(
  val id: Long,
  val parentId: Long?,
  val rootId: Long,
  val name: String,
  val started: Long,
  val finished: Long?,
  val attributes: Map<String, String>,
)

/** One properly nested lane. Concurrent siblings get separate lanes under the same operation. */
internal data class IdeTraceLane(val name: String, val intervals: List<IdeTraceInterval>)

/**
 * Keeps bounded, completed operations until capture drain. Export orders begin/end packets by time
 * and places final metadata on the selected duration bar. Suspension stays inside the interval.
 */
internal class IdeTraceTimeline(
  private val capacity: Int = 20_000,
  private val enclosingReserve: Int = minOf(1024, capacity / 4),
  private val priorityReserve: Int = minOf(4096, (capacity - enclosingReserve) / 4),
) {
  private val intervals = mutableListOf<IdeTraceInterval>()
  private var dropped = 0
  private var reservedDetails = 0

  init {
    require(capacity >= 0)
    require(enclosingReserve in 0..capacity)
    require(priorityReserve in 0..(capacity - enclosingReserve))
  }

  /** A reserved parent keeps its slot while its metadata emits nested detail intervals. */
  internal class DetailReservation internal constructor(internal val owner: IdeTraceTimeline) {
    internal var active = true
  }

  /** Coarse work leaves room for ranked details and the enclosing phase's final summaries. */
  @Synchronized
  internal fun reserveDetail(priority: Boolean): DetailReservation? {
    val detailCapacity = capacity - enclosingReserve - if (priority) 0 else priorityReserve
    if (intervals.size + reservedDetails >= detailCapacity) {
      dropped++
      return null
    }
    reservedDetails++
    return DetailReservation(this)
  }

  /** Admission already counted this slot; nested children can finish before their parent. */
  @Synchronized
  internal fun recordReservedDetail(reservation: DetailReservation, interval: IdeTraceInterval) {
    check(reservation.owner === this && reservation.active)
    reservation.active = false
    reservedDetails--
    intervals += interval
  }

  /** Releases an unused slot after a recording failure. Completed reservations are unchanged. */
  @Synchronized
  internal fun releaseDetail(reservation: DetailReservation) {
    check(reservation.owner === this)
    if (!reservation.active) return
    reservation.active = false
    reservedDetails--
  }

  @Synchronized
  fun record(interval: IdeTraceInterval) {
    if (intervals.size + reservedDetails < capacity) intervals += interval else dropped++
  }

  /** The collapsed parent shows the envelope of recorded work, including gaps and suspension. */
  @Synchronized
  internal fun overview(): IdeTraceInterval? {
    if (intervals.isEmpty()) return null
    val work = intervals.filter { it.finished != null }
    val first = work.minOfOrNull { it.started } ?: intervals.minOf { it.started }
    val last = work.maxOfOrNull { checkNotNull(it.finished) } ?: intervals.maxOf { it.started }
    val request = intervals.firstOrNull { it.name == "refresh" && it.finished != null }
    val finish = intervals.firstOrNull { it.name == "capture.finish" }
    val attributes = buildMap {
      put("operation", "capture.overview")
      put("operation_id", "0")
      put("elapsed_ns", (last - first).toString())
      put("timing", "First to last recorded operation, including gaps and suspension")
      put("dropped_events", dropped.toString())
      request?.attributes?.get("manualRequest")?.let { put("manualRequest", it) }
      finish?.attributes?.get("stop_reason")?.let { put("stop_reason", it) }
      finish?.attributes?.get("partial")?.let { put("partial", it) }
    }
    return IdeTraceInterval(0, null, 0, "capture.overview", first, last, attributes)
  }

  /** Uses ancestry to prevent overlapping sibling operations from looking like nested calls. */
  @Synchronized
  fun lanes(): List<IdeTraceLane> = buildList {
    for ((_, group) in intervals.groupBy { it.rootId }) {
      val ordered =
        group.sortedWith(
          compareBy<IdeTraceInterval> { it.started }
            .thenByDescending { it.finished ?: it.started }
            .thenBy { it.id }
        )
      val root = group.firstOrNull { it.id == it.rootId && it.finished != null } ?: ordered.first()
      val laneContents = mutableListOf<MutableList<IdeTraceInterval>>()
      val stacks = mutableListOf<MutableList<IdeTraceInterval>>()
      for (interval in ordered) {
        if (interval.finished == null) continue
        stacks.forEach { stack ->
          while (
            stack.isNotEmpty() && checkNotNull(stack.last().finished) <= interval.started
          ) stack.removeLast()
        }
        var lane = stacks.indexOfFirst { stack ->
          if (stack.isEmpty()) true
          else
            stack.last().id == interval.parentId &&
              checkNotNull(stack.last().finished) >= interval.finished
        }
        if (lane == -1) {
          lane = stacks.size
          stacks += mutableListOf<IdeTraceInterval>()
          laneContents += mutableListOf<IdeTraceInterval>()
        }
        laneContents[lane] += interval
        stacks[lane] += interval
      }
      if (laneContents.isEmpty()) laneContents += mutableListOf<IdeTraceInterval>()
      laneContents.first().addAll(ordered.filter { it.finished == null })
      laneContents.forEachIndexed { index, contents ->
        val suffix = if (index == 0) "" else " (concurrent ${index + 1})"
        add(IdeTraceLane(ideTraceDisplayName(root.name, root.attributes) + suffix, contents))
      }
    }
  }

  /** Called on IO after AndroidX closes its writer; both writers use desktop System.nanoTime(). */
  fun writeTo(path: Path) {
    val lanes = lanes()
    val overview = overview()
    path.toFile().appendingSink().buffer().use { sink ->
      sink.descriptor(TRACK_BASE, "Metro operations", null)
      lanes.forEachIndexed { index, lane ->
        sink.descriptor(TRACK_BASE + index + 1, lane.name, TRACK_BASE)
      }
      data class Boundary(
        val time: Long,
        val type: Int,
        val track: Long,
        val interval: IdeTraceInterval,
      )
      val boundaries = buildList {
        if (overview != null) {
          if (overview.started == overview.finished) {
            add(Boundary(overview.started, 3, TRACK_BASE, overview))
          } else {
            add(Boundary(overview.started, 1, TRACK_BASE, overview))
            add(Boundary(checkNotNull(overview.finished), 2, TRACK_BASE, overview))
          }
        }
        lanes.forEachIndexed { index, lane ->
          val track = TRACK_BASE + index + 1
          for (interval in lane.intervals) {
            val finished = interval.finished
            if (finished == null || finished == interval.started) {
              add(Boundary(interval.started, 3, track, interval))
            } else {
              add(Boundary(interval.started, 1, track, interval))
              add(Boundary(finished, 2, track, interval))
            }
          }
        }
      }
        .sortedWith(
          compareBy<Boundary> { it.time }
            // End adjacent slices first; equal-time children close before their parents.
            .thenBy { if (it.type == 2) 0 else it.type }
            .thenBy {
              if (it.type == 2) -it.interval.started else -(it.interval.finished ?: it.time)
            }
            .thenBy { if (it.type == 2) -it.interval.id else it.interval.id }
        )
      for (boundary in boundaries) {
        sink.event(
          boundary.time,
          boundary.track,
          boundary.type,
          boundary.interval.name,
          boundary.interval.attributes,
        )
      }
      val lastTime = boundaries.lastOrNull()?.time ?: System.nanoTime()
      sink.event(
        lastTime,
        TRACK_BASE,
        3,
        "Trace summary",
        mapOf(
          "dropped_events" to dropped.toString(),
          "timing" to "wall time, including suspension",
        ),
      )
    }
  }

  private companion object {
    // Separate sequence and UUID namespace from AndroidX's thread tracks in the same file.
    const val SEQUENCE = 2
    const val TRACK_BASE = 0x4d4554524f000000L

    fun BufferedSink.descriptor(uuid: Long, name: String, parent: Long?) {
      packet {
        message(60) {
          number(1, uuid)
          string(2, name)
          parent?.let { number(5, it) }
        }
      }
    }

    fun BufferedSink.event(
      timestamp: Long,
      track: Long,
      type: Int,
      name: String,
      attributes: Map<String, String>,
    ) {
      packet {
        number(8, timestamp)
        message(11) {
          number(9, type.toLong())
          number(11, track)
          if (type != 2) {
            string(22, "metro.ide")
            string(23, ideTraceDisplayName(name, attributes))
            for ((key, value) in attributes) {
              message(4) {
                string(10, key)
                string(6, value)
              }
            }
          }
        }
      }
    }

    /**
     * Encodes the stable Perfetto TrackEvent schema using the existing Wire runtime. Field numbers:
     * https://github.com/google/perfetto/tree/main/protos/perfetto/trace/track_event Non-interned
     * fields keep this sequence independent of AndroidX's serializer state.
     */
    fun BufferedSink.packet(block: ProtoWriter.() -> Unit) {
      val bytes = messageBytes {
        number(10, SEQUENCE.toLong())
        block()
      }
      ProtoAdapter.BYTES.encodeWithTag(ProtoWriter(this), 1, bytes)
    }

    fun ProtoWriter.message(tag: Int, block: ProtoWriter.() -> Unit) =
      ProtoAdapter.BYTES.encodeWithTag(this, tag, messageBytes(block))

    fun ProtoWriter.string(tag: Int, value: String) =
      ProtoAdapter.STRING.encodeWithTag(this, tag, value)

    fun ProtoWriter.number(tag: Int, value: Long) =
      ProtoAdapter.UINT64.encodeWithTag(this, tag, value)

    fun messageBytes(block: ProtoWriter.() -> Unit): ByteString {
      val buffer = Buffer()
      ProtoWriter(buffer).block()
      return buffer.readByteString()
    }
  }
}

/** Stable operation keys stay in metadata while labels describe the work and its subject. */
internal fun ideTraceDisplayName(name: String, attributes: Map<String, String>): String {
  if (name.endsWith(".result")) return name
  attributes["display_name"]?.let {
    return it
  }
  val label =
    when (name) {
      "capture.overview" -> "Metro recorded work"
      "refresh" -> "Refresh Metro graphs"
      "index.candidate" -> "Build graph index"
      "index.classifyPsi" -> "Classify changed source files"
      "index.captureTargets" -> "Capture refresh targets"
      "source.discover" -> "Discover Metro source files"
      "source.scan" -> "Analyze source declarations"
      "source.aggregate" -> "Combine source declarations"
      "source.resolveClasses" -> "Resolve source class dependencies"
      "source.buildOwnershipIndex" -> "Build source ownership index"
      "source.consumerOwnership" -> "Find consumer ownership"
      "source.resolveClassRequests" -> "Resolve class requests"
      "source.collectLibraryInputs" -> "Collect library inputs"
      "source.file.item" -> "Analyze file"
      "source.class.item" -> "Resolve class"
      "source.file.module" -> "Source files by module"
      "source.class.module" -> "Class requests by module"
      "source.file.psi" -> "Load source PSI"
      "source.file.cacheLookup" -> "Read source shard cache"
      "source.file.imports" -> "Read source imports"
      "source.file.annotationScan" -> "Find annotated declarations"
      "source.file.annotationLookup" -> "Resolve annotation name"
      "source.file.typealiasLookup" -> "Resolve annotation type alias"
      "source.file.declarationExtraction" -> "Extract Metro declaration"
      "source.file.dynamicGraphScan" -> "Find dynamic graph factories"
      "source.file.shardConstruction" -> "Construct source shard"
      "source.class.analysisEntry" -> "Enter Kotlin analysis"
      "source.class.analysisSetup" -> "Locate requesting file"
      "source.class.findClass" -> "Find class symbol"
      "source.class.declarationEligibility" -> "Check source declaration"
      "source.class.optionsAndQualifierLookup" -> "Read options and qualifiers"
      "source.class.cacheCheck" -> "Check class binding cache"
      "source.class.bindingConstruction" -> "Build class binding"
      "source.class.dependencyExpansion" -> "Expand class dependencies"
      "source.class.analysisExit" -> "Leave Kotlin analysis"
      "library.resolve" -> "Resolve library dependencies"
      "library.discoverMetadata" -> "Discover library metadata"
      "library.resolveClasses" -> "Resolve library classes"
      "index.captureInputs" -> "Capture graph index inputs"
      "index.seal" -> "Seal graph index"
      "validation" -> "Validate graphs"
      "validation.capture" -> "Capture validation inputs"
      "validation.compute" -> "Compute graph validation"
      "validation.seal" -> "Seal binding graph"
      "validation.publish" -> "Publish validation results"
      "presentation.build" -> "Build editor decorations"
      "presentation.anchors" -> "Resolve editor positions"
      "presentation.resolve" -> "Resolve editor bindings"
      "presentation.captureAnchors" -> "Capture editor positions"
      "presentation.result" -> "Editor decoration result"
      "presentation.publication" -> "Publish editor decorations"
      else -> name
    }
  val subject =
    when (name) {
      "refresh",
      "index.candidate" -> attributes["manualRequest"]?.let { "refresh #$it" }
      "source.scan" -> attributes["files.total"]?.let { "$it files" }
      "capture.overview" -> attributes["manualRequest"]?.let { "refresh #$it" }
      "source.file.item",
      "presentation.build",
      "presentation.anchors" -> attributes["file"]
      "source.class.item" -> attributes["class"]
      "source.file.module",
      "source.class.module" -> attributes["module"]
      "validation.seal" -> attributes["graph"]
      else -> null
    }
  return if (subject == null) label else "$label: $subject"
}
