# Zensical Migration

This project is partially migrated from [MkDocs Material](https://squidfunk.github.io/mkdocs-material/) to [Zensical](https://zensical.org/), the successor project built by the same creators.

## Current State

Zensical is used for:

- **Local development** (`zensical serve`) - faster hot-reload via Rust-based engine
- **CI validation builds** - `zensical build` is used to validate the docs build in CI

MkDocs + MkDocs Material + mike are still used for:

- **Versioned deployments** - `mike deploy` is used to publish versioned docs to `gh-pages`. Mike internally depends on `mkdocs` and `mkdocs-material`, so these packages remain in the requirements.

## Why Partial

Zensical does not yet support versioned docs deployment (the equivalent of [mike](https://github.com/jimporter/mike)). This is [on the Zensical roadmap](https://github.com/zensical/backlog/issues/45) and tracked in [zensical/zensical#201](https://github.com/zensical/zensical/issues/201). Once Zensical supports versioning natively, the remaining MkDocs/mike dependencies can be removed.

## Configuration

The existing `mkdocs.yml` is used as-is by both Zensical and MkDocs - no separate config is needed. Zensical automatically understands the MkDocs Material configuration format.

## Completing the Migration

When Zensical adds versioning support:

1. Remove `mkdocs`, `mkdocs-material`, `mkdocs-material-extensions`, `mike`, and their transitive dependencies from `.github/workflows/mkdocs-requirements.txt`
2. Update `mike deploy` calls in CI workflows (`.github/workflows/docs-site.yml`, `docs-site-manual.yml`) and `scripts/deploy_metro_docs_site.sh` to use Zensical's versioning
3. Update `scripts/delete_old_version_docs.sh` to use Zensical's equivalent
4. Update `extra.version.provider` in `mkdocs.yml` if needed
5. Remove this file
