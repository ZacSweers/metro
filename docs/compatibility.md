# Kotlin Compatibility

The Kotlin compiler plugin API is not a stable API, so not every version of Metro will work with every version of the Kotlin compiler.

Starting with Metro `0.6.9`, Metro tries to support forward compatibility on a best-effort basis. Usually, it's `N+.2` (so a Metro version built against Kotlin `2.3.0` will try to support up to `2.3.20`.

Pre-release versions are normally only tested during their development cycle. After their stable release, Metro should continue to work with them but they will no longer be tested against. Their last tested release is indicated by putting the version in brackets like `[0.9.1]`.

| Kotlin version  | Metro versions (inclusive) | Notes                                |
|-----------------|----------------------------|--------------------------------------|
| 2.3.20-dev-5437 | 0.8.1 -                    |                                      |
| 2.3.0           | 0.9.1 -                    | [1]                                  |
| 2.3.0-RC3       | 0.6.9, 0.6.11 - [0.9.2]    |                                      |
| 2.3.0-RC2       | 0.6.9, 0.6.11 - [0.9.2]    |                                      |
| 2.3.0-RC        | 0.6.9, 0.6.11 - [0.9.2]    | Reporting doesn't work until `0.7.3` |
| 2.3.0-Beta2     | 0.6.9, 0.6.11 - [0.9.2]    | Reporting doesn't work until `0.7.3` |
| 2.3.0-Beta1     | 0.6.9, 0.6.11 - [0.9.2]    |                                      |
| 2.2.21          | 0.6.6 -                    |                                      |
| 2.2.20          | 0.6.6 -                    |                                      |
| 2.2.10          | 0.4.0 - 0.6.5              |                                      |
| 2.2.0           | 0.4.0 - 0.6.5              |                                      |
| 2.1.21          | 0.3.1 - 0.3.8              |                                      |
| 2.1.20          | 0.1.2 - 0.3.0              |                                      |

[1]: Metro versions 0.6.9–0.9.0 had a [version comparison bug](https://github.com/ZacSweers/metro/issues/1544) that caused them to incorrectly select a compat module for Kotlin 2.2.20 when running on the Kotlin 2.3.0 final release. This was fixed in 0.9.1.

Some releases may introduce prohibitively difficult breaking changes that require companion release, so check Metro's open PRs for one targeting that Kotlin version for details. There is a tested versions table at the bottom of this page that is updated with each Metro release.

## Tested Versions

[![CI](https://github.com/ZacSweers/metro/actions/workflows/ci.yml/badge.svg)](https://github.com/ZacSweers/metro/actions/workflows/ci.yml)

The following Kotlin versions are tested via CI:

| Kotlin Version  |
|-----------------|
| 2.3.20-dev-5437 |
| 2.3.0-RC3       |
| 2.3.0-RC2       |
| 2.3.0-RC        |
| 2.3.0-Beta2     |
| 2.3.0-Beta1     |
| 2.3.0           |
| 2.2.21          |
| 2.2.20          |

!!! note
    Versions without dedicated compiler-compat modules will use the nearest available implementation _below_ that version. See [`compiler-compat/version-aliases.txt`](https://github.com/ZacSweers/metro/blob/main/compiler-compat/version-aliases.txt) for the full list.

## What about Metro's stability?

See the [stability docs](stability.md).
