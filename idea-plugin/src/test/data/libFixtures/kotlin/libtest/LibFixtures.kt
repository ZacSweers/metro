// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package libtest

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Origin
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

interface LibService

interface LibAnalytics

/** Resolvable on demand as a constructor-injected library class. */
@Inject @SingleIn(AppScope::class) class LibHttpClient

@Inject @ContributesBinding(AppScope::class) class LibServiceImpl : LibService

@Inject @ContributesIntoSet(AppScope::class) class LibAnalyticsImpl : LibAnalytics

// Explicit binding<T>() — the bound type is unrecoverable from supertypes alone, and binaries
// don't carry the annotation's type argument. Real Metro-compiled libraries expose it through a
// generated nested MetroContribution interface with @Binds members, replicated here by hand.
interface LibExplicit

interface LibMarker

@Inject
@ContributesBinding(AppScope::class, binding = binding<LibExplicit>())
class LibExplicitImpl : LibExplicit, LibMarker {
  interface MetroContributionToAppScope {
    @Binds val LibExplicitImpl.bindLibExplicit: LibExplicit
  }
}

// Replicates the compiler's generate-contribution-providers output: a holder class with a
// per-scope container object carrying @Origin and the actual @Provides members, letting the
// implementation stay internal.
interface LibContained

internal class LibContainedImpl : LibContained

abstract class LibContainedImplContributions {
  @Origin(LibContainedImpl::class)
  object ToAppScope {
    @Provides fun provideLibContained(): LibContained = LibContainedImpl()
  }
}

// Contributed only via an internal hint, which consuming modules must not see
interface LibHidden

@Inject @ContributesBinding(AppScope::class) class LibHiddenImpl : LibHidden
