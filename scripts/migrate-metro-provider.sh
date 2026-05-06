#!/usr/bin/env bash
# Migrates `dev.zacsweers.metro.Provider<T>` usages in a Kotlin codebase to the
# function-syntax form `() -> T`.
#
# Usage:
#   scripts/migrate-metro-provider.sh [directory]
#
# Only touches .kt files that import Metro's Provider (explicit or via a wildcard
# `dev.zacsweers.metro.*` import) AND do NOT also import a non-Metro Provider
# (javax.inject, jakarta.inject, com.google.inject, dagger.internal). Files with
# ambiguous Provider imports are skipped and logged so they can be reviewed by
# hand.
#
# Nested generics (e.g. `Provider<Lazy<Foo>>`, `Map<K, Provider<V>>`,
# `Provider<Provider<T>>`) are handled via a recursive regex that loops until
# no further substitutions occur. `Provider<*>` is intentionally left alone
# because `() -> *` is not valid Kotlin.
#
# Requires: perl (standard on macOS/Linux), grep, find.

set -euo pipefail

ROOT="${1:-.}"

if ! command -v perl >/dev/null 2>&1; then
  echo "error: perl is required" >&2
  exit 1
fi

migrated=0
skipped=0

while IFS= read -r -d '' f; do
  # Must reference Metro's Provider (explicit or wildcard import)
  if ! grep -qE '^import dev\.zacsweers\.metro\.(Provider$|\*$)' "$f"; then
    continue
  fi

  # Skip ambiguous files that also import another Provider type — we can't
  # disambiguate those without full type analysis.
  if grep -qE '^import (javax\.inject|jakarta\.inject|com\.google\.inject|dagger\.internal)\.Provider($|;)' "$f"; then
    echo "skip (mixed Provider imports): $f"
    skipped=$((skipped + 1))
    continue
  fi

  # Rewrite `Provider<T>` → `() -> T`. The recursive regex matches balanced
  # `<...>` groups so nested generics are handled correctly. The `1 while`
  # loop reapplies the substitution until stable so cases like
  # `Provider<Provider<Foo>>` fully unwrap to `() -> () -> Foo`. `-0777`
  # slurps the whole file so matches can span lines. The `(?!\*>)` lookahead
  # skips `Provider<*>` since `() -> *` is not valid Kotlin.
  perl -i -0777 -pe '1 while s/\bProvider<(?!\*>)((?:[^<>]++|<(?1)>)++)>/() -> $1/g' "$f"

  # If no `Provider` identifier remains outside the import line (no `Provider<*>`,
  # no `Provider { ... }` factory calls, no `Provider::invoke` refs, etc.), drop
  # the now-unused import. Otherwise leave it for manual review.
  if ! grep -vE '^import dev\.zacsweers\.metro\.Provider$' "$f" | grep -qE '\bProvider\b'; then
    perl -i -ne 'print unless /^import dev\.zacsweers\.metro\.Provider$/' "$f"
  fi

  echo "migrated: $f"
  migrated=$((migrated + 1))
done < <(find "$ROOT" -type f -name '*.kt' -print0)

echo
echo "migrated: $migrated file(s)"
echo "skipped:  $skipped file(s)"
