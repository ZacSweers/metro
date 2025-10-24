# Kotlin Compatibility

The Kotlin compiler plugin API is not a stable API, so not every version of Metro will work with every version of the Kotlin compiler.

Starting with Metro `0.6.9`, Metro tries to support forward compatibility on a best-effort basis. Some releases may introduce prohibitively difficult breaking changes that require companion release, so check Metro's open PRs for one targeting that Kotlin version for details.

| Kotlin version | Metro versions (inclusive) |
|----------------|----------------------------|
| 2.3.0-Beta1    | 0.6.9, 0.6.11 -            |
| 2.2.21         | 0.6.6 -                    |
| 2.2.20         | 0.6.6 -                    |
| 2.2.10         | 0.4.0 - 0.6.5              |
| 2.2.0          | 0.4.0 - 0.6.5              |
| 2.1.21         | 0.3.1 - 0.3.8              |
| 2.1.20         | 0.1.2 - 0.3.0              |


## Tested Versions

[![CI](https://github.com/ZacSweers/metro/actions/workflows/ci.yml/badge.svg)](https://github.com/ZacSweers/metro/actions/workflows/ci.yml)

The following Kotlin versions are tested via CI:

| Kotlin Version |
|----------------|
| 2.3.0-Beta1    |
| 2.2.21         |
| 2.2.20         |

> **Note:** Versions without dedicated compiler-compat modules will use the nearest available implementation. See `compiler-compat/version-aliases.txt` for the full list.

