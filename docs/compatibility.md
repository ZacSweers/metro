# Kotlin Compatibility

The Kotlin compiler plugin API is not a stable API, so not every version of Metro will work with every version of the Kotlin compiler.

Starting with Metro `0.6.9`, Metro tries to support forward compatibility on a best-effort basis. Usually, it's `N+.2` (so a Metro version built against Kotlin `2.3.0` will try to support up to `2.3.20`.

Pre-release versions are normally only tested during their development cycle. After their stable release, Metro should continue to work with them but they will no longer be tested against. Their last tested release is indicated by putting the version in brackets like `[0.9.1]`.

| Kotlin version  | Metro versions (inclusive) | Notes                                |
|-----------------|----------------------------|--------------------------------------|
| 2.3.20-Beta1    | 0.9.5 -                    |                                      |
| 2.3.20-dev-7874 | 0.9.5 -                    |                                      |
| 2.3.20-dev-4962 | 0.9.5 -                    |                                      |
| 2.3.20-dev-5437 | 0.9.1 - [0.9.4]            | [2]                                  |
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
[1]: Metro switched `2.3.20-dev-5437` support/testing to `2.3.20-dev-4962` after `0.9.4`. This is necessary to avoid ephemeral dev build artifacts and stick to persistent ones.

Some releases may introduce prohibitively difficult breaking changes that require companion release, so check Metro's open PRs for one targeting that Kotlin version for details. There is a tested versions table at the bottom of this page that is updated with each Metro release.

## IDE Support

IDEs have their own compatibility story with Kotlin versions. The Kotlin IDE plugin embeds Kotlin versions built from source, so Metro's IDE support selects the nearest compatible version and tries to support the latest stable IntelliJ and Android Studio releases + the next IntelliJ EAP release.

!!! note "IntelliJ's unusual Kotlin tags"

    IntelliJ sometimes publishes releases that do not use published Kotlin repo tags. For example, usually it uses a `2.3.20-dev-1234` version that exists on GitHub and is published to the Kotlin dev maven repo. However, sometimes it uses something like a `2.3.20-ij253-45` that (at the time of writing) doesn't appear to correspond to any public tag.
    
    While these do have artifacts in a known maven repository, it's unclear what git sha in the Kotlin repo these are from and thus unclear where they are in the _order_ of dev builds. For now, Metro handles this by selecting the highest available `-dev-***` build for this base version (i.e. `2.3.20`), before then falling back to the other types (stable, beta, etc.)

    Please star this issue: https://youtrack.jetbrains.com/issue/KTIJ-37076

!!! note "Android Studio's custom versions"

    Android Studio uses a custom version of the Kotlin compiler that does not necessarily correspond to any real published Kotlin version. For example, Android Studio Otter 2 (at the time of writing) declares `2.2.255-dev-255`, which isn't a real version. Android Studio Panda canaries declare `2.3.255-dev-255`. Similar to the mystery `ij` tags for IntelliJ versions above, metro best-effort supports this by just picking the nearest available compat context that is lower that Studio's declared version.

    Please star this issue: https://issuetracker.google.com/issues/474940910

## Tested Versions

[![CI](https://github.com/ZacSweers/metro/actions/workflows/ci.yml/badge.svg)](https://github.com/ZacSweers/metro/actions/workflows/ci.yml)

The following Kotlin versions are tested via CI:

| Kotlin Version  |
|-----------------|
| 2.3.20-dev-5437 |
| 2.3.0           |
| 2.2.21          |
| 2.2.20          |

!!! note
    Versions without dedicated compiler-compat modules will use the nearest available implementation _below_ that version. See [`compiler-compat/version-aliases.txt`](https://github.com/ZacSweers/metro/blob/main/compiler-compat/version-aliases.txt) for the full list.

## What about Metro's stability?

See the [stability docs](stability.md).
