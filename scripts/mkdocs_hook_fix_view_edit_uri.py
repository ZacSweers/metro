"""MkDocs hook to fix edit URLs for copied documentation files.

This hook intercepts the page rendering process and updates the
edit_url to point to the actual source files in their original locations.

For more information on MkDocs hooks:
https://www.mkdocs.org/user-guide/configuration/#hooks
"""

# Mapping of copied doc files (as they appear in docs/) to their actual source locations
# Source of these paths can be found at `scripts/copy_docs_files.sh`
SOURCE_PATH_MAPPING = {
    'changelog.md': 'CHANGELOG.md',
    'contributing.md': '.github/CONTRIBUTING.md',
    'code-of-conduct.md': '.github/CODE_OF_CONDUCT.md',
    'samples.md': 'samples/README.md',
    'metrox-android.md': 'metrox-android/README.md',
    'metrox-viewmodel.md': 'metrox-viewmodel/README.md',
    'metrox-viewmodel-compose.md': 'metrox-viewmodel-compose/README.md',
}

def on_page_context(context, page, config, nav=None, **kwargs):
    """
    Hook event handler that corrects edit URLs for pages with copied sources.
    https://www.mkdocs.org/dev-guide/plugins/#on_page_context
    """
    source_file = page.file.src_path
    
    # Check if this is one of the files we need to redirect
    if source_file in SOURCE_PATH_MAPPING:
        actual_source = SOURCE_PATH_MAPPING[source_file]
        
        # Only update if repo_url is configured (required for edit links)
        if config.get('repo_url'):
            # For actual source files, use edit_uri without the 'docs/' part
            # since they live at the repo root or in specific directories
            edit_uri = 'edit/main/'
            
            # Construct the correct edit URL for the actual source file
            # Example result: https://github.com/ZacSweers/metro/edit/main/CHANGELOG.md
            page.edit_url = (
                config['repo_url'].rstrip('/') + '/' +
                edit_uri.rstrip('/') + '/' +
                actual_source
            )
    
    return context
