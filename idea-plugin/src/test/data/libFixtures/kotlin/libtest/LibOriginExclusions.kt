// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package libtest

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Origin
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.internal.MetroContribution

/** Keeps origin-exclusion bindings out of unrelated library fixture graphs. */
abstract class LibOriginScope

class LibOriginTrigger

@Origin(LibOriginTrigger::class)
class LibOriginReplacement

@BindingContainer
@ContributesTo(LibOriginScope::class)
object LibOriginOriginalBindings {
  @Provides fun originalValue(): String = "original"
}

/** Mirrors a generated provider container whose origin has its own origin. */
abstract class LibOriginReplacementProviders {
  @MetroContribution(LibOriginScope::class)
  @Origin(LibOriginReplacement::class, context = "contribution_provider")
  @BindingContainer
  @ContributesTo(LibOriginScope::class, replaces = [LibOriginOriginalBindings::class])
  object ToScope {
    @Provides fun replacementValue(): String = "replacement"
  }
}
