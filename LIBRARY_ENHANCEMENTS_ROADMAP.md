# Library Enhancements Roadmap

Features marked with [LIBRARY_ENHANCEMENT: ...] tags in the book and code are planned but not yet implemented. Status is tracked here.

## NUMA_AWARE_OBJECT_ALLOCATOR

Object allocator that transparently places each allocation on the NUMA node local to the requesting thread.

**Status:** Planned

## NUMA_MEMORY_INTERLEAVE

Interleaved memory allocation across a chosen set of NUMA nodes to spread bandwidth-bound workloads evenly.

**Status:** Planned

## NUMA_PAGE_MIGRATION

Migration of already-allocated pages between NUMA nodes when a thread's affinity moves it to a different node.

**Status:** Planned

## NUMA_PER_THREAD_POLICY

Per-thread NUMA memory policies (bind, preferred, interleave) instead of a single process-wide policy.

**Status:** Planned

## NUMA_STATISTICS_MONITORING

Runtime NUMA statistics — local vs. remote access ratios and per-node allocation counters — exposed through the monitoring API.

**Status:** Planned

## SIMD_CAPABILITY_DETECTION

Runtime detection of SIMD instruction-set support (x86 AVX/AVX2/AVX-512, ARM NEON/SVE) exposed as `AffinityLibrary.getSIMDCapabilities()`.

**Status:** Planned
