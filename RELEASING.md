Releasing
=========

1. Update the `CHANGELOG.md` for the impending release.
    - If the Kotlin version changed, update `compatibility.md` docs too.
2. Run `./release.sh <version|--patch|--minor|--major>`.

The release script promotes the changelog, publishes artifacts, prepares the next snapshot,
pushes the release commits and tag, and creates the GitHub release.
