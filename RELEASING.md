Releasing
=========

1. Update the `CHANGELOG.md` for the impending release.
    - If the Kotlin version changed, update `compatibility.md` docs too.
2. Run `./release.sh <version|--patch|--minor|--major>`.

The release script promotes the changelog, publishes artifacts, prepares the next snapshot,
pushes the release commits and tag, and creates the GitHub release.

## IDEA plugin

Pass `--publish-idea-plugin` to `release.sh` to publish the plugin with a stable release. JetBrains Marketplace and the IDE use that version's section of `CHANGELOG.md`. EAP snapshots use **Unreleased**.

Tag changes shared by the compiler and the IDEA plugin with `**[core]**`. Use `**[IDEA]**` for plugin-specific changes. The plugin release notes include only entries with these exact tags and the **Contributors** section when present.
