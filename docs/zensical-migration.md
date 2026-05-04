# Zensical Migration

This project is partially migrated from [MkDocs Material](https://squidfunk.github.io/mkdocs-material/) to [Zensical](https://zensical.org/), the successor project built by the same creators.

## Current State

Zensical is used for:

- **Local development** (`zensical serve`) - faster hot-reload via Rust-based engine
- **CI validation builds** - `zensical build` is used to validate the docs build in CI
- **Versioned deployments** - via [squidfunk's mike fork](https://github.com/squidfunk/mike), a temporary Zensical-compatible fork of mike. This fork still depends on `mkdocs` internally, so MkDocs and its dependencies remain in requirements.

MkDocs + MkDocs Material are still installed as transitive dependencies of the mike fork.

## Why Partial

Zensical does not yet support native versioned docs deployment. The squidfunk mike fork is an interim solution. Native versioning is [on the Zensical roadmap](https://zensical.org/about/roadmap/#versioning). Once Zensical supports versioning natively, the mike fork and all MkDocs dependencies can be removed.

## Configuration

The existing `mkdocs.yml` is used as-is by both Zensical and MkDocs - no separate config is needed. Zensical automatically understands the MkDocs Material configuration format.

## Completing the Migration

When Zensical adds native versioning support:

1. Remove `mike` (squidfunk fork), `mkdocs`, `mkdocs-material`, `mkdocs-material-extensions`, and their transitive dependencies from `.github/workflows/mkdocs-requirements.txt`
2. Update `mike deploy` calls in CI workflows (`.github/workflows/docs-site.yml`, `docs-site-manual.yml`) and `scripts/deploy_metro_docs_site.sh` to use Zensical's versioning
3. Update `scripts/delete_old_version_docs.sh` to use Zensical's equivalent
4. Update `extra.version.provider` in `mkdocs.yml` if needed
5. Ensure the `gh-pages` branch root files are preserved or migrated: `index.html` (redirects to `latest/`) and `404.html` (smart redirect for versioned docs). If Zensical provides native equivalents, migrate to those.
6. Remove this file
