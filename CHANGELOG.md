# Changelog

All notable changes to this project are documented in this file.

## [1.2.0] — 2026-08-09

This release makes the library the official companion repository for
**Low Latency Programming in Java — Book 1: CPU Affinity, NUMA, and Thread
Placement** by Amardeep Mond. All changes are additive; no existing API was
modified or removed.

### Added
- `AffinityLibrary.setThreadAffinity(long threadId, int cpu)` — int-core
  convenience overload that pins a thread to a single CPU core.
- `AffinityLibrary.setCurrentThreadAffinity(int cpu)` — int-core convenience
  overload for the current thread.
- `AffinityLibrary.getNUMAManager()` — direct accessor for the `NUMAManager`
  backing the library's NUMA operations.
- `BookExamplesTest` — book-compatibility test suite containing the printed
  book samples verbatim, wired into the build as a surefire execution that
  runs on every platform. It permanently guarantees the printed book compiles
  and runs against this library.

### Changed
- Maven coordinates renamed from `com.hft.systems:affinity-library` to
  `com.faster.affinity:faster-hft-affinity` to match the name used in the book
  (the old coordinates were never published to any public repository).
- `Automatic-Module-Name` aligned to `com.faster.affinity` (the package root).

### Removed / cleanup
- Deleted stray one-off validation classes from the repository root
  (`QuickCompileTest`, `ValidationTest`, `ValidateFixesTest`,
  `OperationResultMigrationTest`); `ComprehensiveAffinityTest` moved to
  `com.faster.affinity.examples` and now runs straight from the fat JAR.
- Deleted `*.java.old` backup files under `src/main`.
- `.idea/` is no longer tracked and is now ignored, along with `*.java.old`.

## [1.1.0] and earlier

Predate this changelog. See the git history.
