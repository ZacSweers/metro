#!/bin/bash

# Validator script to ensure copy_docs_files.sh and mkdocs_hook_fix_view_edit_uri.py stay in sync
#
# This script is run in CI to verify that whenever a new documentation file is
# added to the copy_docs_files.sh script, a corresponding mapping entry is added
# to SOURCE_PATH_MAPPING in mkdocs_hook_fix_view_edit_uri.py.
#
# Purpose: Prevent broken edit/view links on the documentation site by catching
# missing mappings at build time rather than after deployment.
#
# Usage: ./scripts/copy_docs_files_mapping_validator.sh
#
# Exit codes:
#   0 - All mappings are present and valid
#   1 - One or more files are copied but have no mapping

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COPY_SCRIPT="$SCRIPT_DIR/copy_docs_files.sh"
HOOKS_FILE="$SCRIPT_DIR/mkdocs_hook_fix_view_edit_uri.py"

echo "Validating edit URL mappings..."
echo "  Copy script: $COPY_SCRIPT"
echo "  Hooks file: $HOOKS_FILE"
echo ""

# Extract destination filenames from copy_docs_files.sh
# Looks for patterns like: docs/filename.md (after cp or > operators)
COPY_DESTINATIONS=$(grep -E '(> |docs/).*\.md' "$COPY_SCRIPT" | \
    grep -oE 'docs/[a-zA-Z0-9_-]+\.md' | \
    sed 's|docs/||' | \
    sort | uniq)

# Extract keys from SOURCE_PATH_MAPPING in mkdocs_hooks.py
# Looks for dictionary keys like: 'filename.md': 'path/to/source'
MAPPED_FILES=$(grep -oE "^    '[a-zA-Z0-9_.-]+\.md':" "$HOOKS_FILE" | \
    sed "s/^    '//; s/':.*//" | \
    sort | uniq)

# Find files that are copied but not mapped
MISSING_MAPPINGS=""
for dest in $COPY_DESTINATIONS; do
    if ! echo "$MAPPED_FILES" | grep -q "^$dest$"; then
        MISSING_MAPPINGS="$MISSING_MAPPINGS
  - $dest"
    fi
done

# Find files that are mapped but not copied (orphaned mappings)
ORPHANED_MAPPINGS=""
for mapped in $MAPPED_FILES; do
    if ! echo "$COPY_DESTINATIONS" | grep -q "^$mapped$"; then
        ORPHANED_MAPPINGS="$ORPHANED_MAPPINGS
  - $mapped"
    fi
done

# Report results
echo "Copied files found: $(echo "$COPY_DESTINATIONS" | wc -l)"
echo "Mapped files found: $(echo "$MAPPED_FILES" | wc -l)"
echo ""

# Check for mismatches
VALIDATION_PASSED=true

if [ -n "$MISSING_MAPPINGS" ]; then
    VALIDATION_PASSED=false
    echo "❌ MISSING MAPPINGS"
    echo ""
    echo "The following files are copied in copy_docs_files.sh but have no"
    echo "corresponding mapping in SOURCE_PATH_MAPPING in mkdocs_hook_fix_view_edit_uri.py:"
    echo ""
    echo "$MISSING_MAPPINGS"
    echo ""
    echo "Fix: Add the missing file(s) to SOURCE_PATH_MAPPING in mkdocs_hook_fix_view_edit_uri.py:"
    echo ""
    echo "  SOURCE_PATH_MAPPING = {"
    for file in $(echo "$MISSING_MAPPINGS" | grep -oE '[a-zA-Z0-9_.-]+\.md' | sort); do
        echo "      '$file': 'path/to/source/$file',"
    done
    echo "      ..."
    echo "  }"
    echo ""
fi

if [ -n "$ORPHANED_MAPPINGS" ]; then
    VALIDATION_PASSED=false
    echo "❌ ORPHANED MAPPINGS"
    echo ""
    echo "The following files are mapped in mkdocs_hook_fix_view_edit_uri.py but are NO LONGER"
    echo "being copied in copy_docs_files.sh. Remove these stale mappings:"
    echo ""
    echo "$ORPHANED_MAPPINGS"
    echo ""
    echo "Fix: Remove the orphaned entries from SOURCE_PATH_MAPPING in mkdocs_hook_fix_view_edit_uri.py"
    echo ""
fi

# Final result
if [ "$VALIDATION_PASSED" = true ]; then
    echo "✓ All copied files have corresponding mappings in mkdocs_hook_fix_view_edit_uri.py"
    echo "✓ All mappings correspond to actual copied files"
    exit 0
else
    echo "VALIDATION FAILED: Please fix the issues above to keep both files in sync"
    exit 1
fi
