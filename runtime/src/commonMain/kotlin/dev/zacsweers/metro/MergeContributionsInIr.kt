// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Opts the annotated dependency graph out of FIR contribution-supertype merging. Contributions are
 * still merged into the graph during IR, so the generated graph implementation behaves the same at
 * runtime.
 *
 * Use this when a graph aggregates so many contributions that the merged supertype list approaches
 * JVM signature-size limits or causes Kotlin metadata to balloon.
 *
 * **This is a delicate API.** Skipping FIR-side merging means contributions are not visible in the
 * graph's Kotlin metadata. Side effects include:
 * - Code that consumes the graph as an [Includes] dependency will not see contributed members.
 * - IDE support (if enabled) will not surface contributed supertypes on the graph type.
 * - Kotlin/Native ObjC framework export will not include contributed interfaces in the graph's
 *   superprotocol list.
 *
 * Pair with the `merged-supertype-chunk-size` compiler option for very large graphs that exceed JVM
 * signature-byte limits even after IR-only merging.
 */
@DelicateMetroApi
@ExperimentalMetroApi
@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class MergeContributionsInIr
