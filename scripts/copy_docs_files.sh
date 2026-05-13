#!/bin/bash

# Common script for MkDocs documentation build tasks
# Used by both CI workflows and local development

set -e

# Convert GitHub-style admonitions (> [!NOTE], > [!TIP], etc.) to MkDocs admonition syntax
convert_github_admonitions() {
  python3 -c "
import re, sys

with open(sys.argv[1]) as f:
    content = f.read()

def convert(m):
    kind = m.group(1).lower()
    body = m.group(2)
    lines = []
    for line in body.split('\n'):
        if line.startswith('> '):
            lines.append('    ' + line[2:])
        elif line == '>':
            lines.append('')
        else:
            break
    return '!!! ' + kind + '\n' + '\n'.join(lines)

content = re.sub(r'> \[!(NOTE|TIP|WARNING|IMPORTANT|CAUTION)\]\n((?:>.*\n?)*)', convert, content, flags=re.IGNORECASE)

with open(sys.argv[1], 'w') as f:
    f.write(content)
" "$1"
}

# Copy documentation files to mkdocs directory
echo "Copying documentation files to mkdocs site..."

# Copy in special files that GitHub wants in the project root.
# Prepend front matter to hide the navigation sidebar on single-page tabs
{ echo '---'; echo 'hide:'; echo '  - navigation'; echo '---'; echo; cat CHANGELOG.md; } > docs/changelog.md
cp .github/CONTRIBUTING.md docs/contributing.md
cp samples/README.md docs/samples.md
cp .github/CODE_OF_CONDUCT.md docs/code-of-conduct.md
cp metrox-android/README.md docs/metrox-android.md
cp metrox-viewmodel/README.md docs/metrox-viewmodel.md
cp metrox-viewmodel-compose/README.md docs/metrox-viewmodel-compose.md

# Convert GitHub admonitions to MkDocs syntax in copied files
convert_github_admonitions docs/contributing.md
convert_github_admonitions docs/samples.md
convert_github_admonitions docs/code-of-conduct.md
convert_github_admonitions docs/metrox-android.md
convert_github_admonitions docs/metrox-viewmodel.md
convert_github_admonitions docs/metrox-viewmodel-compose.md

echo "Copying documentation files complete!"