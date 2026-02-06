# IDE Integration Tests

This directory contains integration tests that verify Metro's compiler plugin works correctly in real IDE environments. The tests launch actual IntelliJ IDEA and Android Studio instances, open a test project with Metro applied, and verify that FIR analysis with Metro's generators and checkers does not crash.

## Directory Structure

```
ide-integration-tests/
├── src/                            # Tests using IntelliJ Platform Gradle Plugin
├── test-project/                   # Sample project that applies the Metro plugin
├── ide-versions.txt                # Configuration file listing IDE versions to test
├── list-android-studio-versions.sh # Lists available AS versions from JetBrains
└── download-ides.sh                # Downloads IDEs to cache (parallel, resumable)
```

## Running Tests

```bash
# From this directory
./gradlew test
```

The tests automatically publish Metro to `build/functionalTestRepo` before running.

## Pre-downloading IDEs

For faster test runs (especially in CI), pre-download IDEs using the download script:

```bash
# Download all IDEs listed in ide-versions.txt
./download-ides.sh

# Dry-run to see what would be downloaded
./download-ides.sh --dry-run

# Force re-download even if cached
./download-ides.sh --force

# Control parallel downloads (default: 4)
./download-ides.sh --jobs 8
```

**For maximum speed**, install [aria2](https://aria2.github.io/):

```bash
brew install aria2  # macOS
apt install aria2   # Linux
```

With aria2, downloads use up to 16 parallel connections per file (like [xcodes](https://github.com/XcodesOrg/xcodes)).

## Configuration

### `ide-versions.txt`

This file defines which IDE versions to test. Format:

```
<product>:<version>[:<filename_prefix>]
```

- **product**: `IU` (IntelliJ Ultimate) or `AS` (Android Studio)
- **version**: The IDE version/build number
- **filename_prefix**: (Optional) Required only for AS prerelease builds

---

## Runbook: Adding New IDE Versions

### Adding a New IntelliJ IDEA Version

1. Find the marketing version (e.g., `2025.3.2`) from [JetBrains Toolbox](https://www.jetbrains.com/idea/download/) or the [releases page](https://www.jetbrains.com/idea/download/other.html).

2. Add a line to `ide-versions.txt`:
   ```
   IU:2025.3.2
   ```

3. Pre-download (the script auto-resolves build numbers from JetBrains API):
   ```bash
   ./download-ides.sh
   ```

4. Run the test:
   ```bash
   ./gradlew test
   ```

---

### Adding a New Android Studio Version

1. Run the helper script to see available versions:
   ```bash
   ./list-android-studio-versions.sh
   ```

   Example output:
   ```
   ## Stable

     Android Studio Otter 3 Feature Drop | 2025.2.3
       Version: 2025.2.3.9
       ide-versions.txt: AS:2025.2.3.9

   ## Release Candidate

     Android Studio Panda 1 | 2025.3.1 RC 1
       Version: 2025.3.1.6
       ide-versions.txt: AS:2025.3.1.6:android-studio-panda1-rc1
   ```

2. Copy the `ide-versions.txt:` line for the version you want and add it to `ide-versions.txt`.

3. **For preview builds** (RC, Beta, Canary), you must pre-download:
   ```bash
   ./download-ides.sh
   ```

   This is required because preview builds have non-standard filenames that the IDE Starter can't resolve automatically.

4. Run the test:
   ```bash
   ./gradlew test
   ```

---

## Troubleshooting

**404 errors downloading Android Studio:**
- For preview builds, ensure you have the filename prefix in `ide-versions.txt`
- Run `./download-ides.sh` before running tests

**Slow downloads:**
- Install aria2 for 16x parallel connections: `brew install aria2`
- Use `./download-ides.sh` to pre-cache before test runs

**Tests timing out:**
- IDE download + Gradle import can be slow on first run
- The default timeout is 15 minutes per test
- Pre-download IDEs to avoid timeout during test

**Metro extensions not loaded:**
- Check that `kotlin.k2.only.bundled.compiler.plugins.enabled` is set to `false` in the test's VM options
- The test will fail with a clear message if extensions weren't enabled

**Stale Metro artifacts:**
- The tests automatically run `:installForFunctionalTest` via the included build
- If issues persist, manually run `../gradlew :installForFunctionalTest`
