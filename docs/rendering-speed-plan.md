# Rendering Speed Plan

## Goal

Reduce preview and export time without destabilizing rendering correctness, color handling, or delivery formats.

## Principles

- Profile before changing architecture.
- Prefer improvements that help both preview and export.
- Land low-risk wins before attempting backend rewrites.
- Keep output identical unless a mode is explicitly marked as draft or fast.

## Phase 0: Measurement And Baseline

### Objectives

- Determine whether the main bottleneck is frame materialization, tape/image IO, compositing, or FFmpeg encoding.
- Establish a repeatable benchmark project and report format.

### Work

- Add timing instrumentation around:
  - `DeferredVideo.BitmapBackend.materializeFrame()`
  - page cache hits and misses
  - flashing overlay generation
  - tape overlay reads
  - `VideoWriter.write()`
- Log aggregate timings for preview and export separately.
- Add a headless CLI render path with optional reference-hash verification so benchmark runs can be repeated without the UI and can fail fast on output drift.
- Create one or two benchmark projects:
  - mostly text and static pages
  - effects-heavy project with flashing text, blends, tape overlays, and scaled images

### Exit Criteria

- We can answer where time is spent for at least two representative workloads.
- We have before/after numbers for future phases.

### Current Findings

- Baseline export on `Barely There Credits End Credits.mp4` is renderer-bound, not encoder-bound.
- Profile summary from April 22, 2026:
  - `backend.materializeFrame`: about 1,130,829 ms across 6,600 frames
  - `backend.obtainStaticProgressiveFrame`: about 1,127,249 ms across 6,600 frames
  - `writer.write`: about 25,776 ms across 6,600 frames
  - `writer.queueWait`: about 1,028,722 ms across 6,600 frames
- Implication:
  - the encoder is mostly idle waiting for frames
  - hardware encoding alone will not materially improve this workload
  - the next work should focus on renderer throughput and finer-grained profiling inside static frame materialization

## Phase 1: Renderer Breakdown And CPU Throughput

### Objectives

- Identify which sub-steps inside static progressive frame generation dominate cost.
- Increase frame throughput before attempting backend rewrites.

### Work

- Add finer instrumentation inside `obtainStaticProgressiveFrame()` for:
  - blank-frame fast path
  - flashing-text composition
  - cached static render reuse
  - generic canvas composition
  - canvas-to-user conversion
- Parallelize frame materialization with a bounded worker queue once the sub-step profile is in place.
- Keep writer ordering deterministic and memory growth bounded.

### Risks

- Added profiling noise can make logs harder to read if the bucket list grows too large.
- Parallel workers can increase RAM pressure and expose cache contention.

### Exit Criteria

- We can say which internal rendering sub-steps dominate `backend.obtainStaticProgressiveFrame`.
- CPU-bound exports are measurably faster on the benchmark project.

## Phase 2: Cheap Wins In Export

### Objectives

- Speed up delivery without changing the rendering backend.

### Work

- Add optional hardware encoder presets where available:
  - `h264_nvenc`
  - `hevc_nvenc`
  - `h264_qsv`
  - `hevc_qsv`
  - `h264_amf`
  - `hevc_amf`
- Detect supported encoders at runtime and expose them as preferred fast-export options.
- Keep the current software encoder fallback path.
- Validate color metadata and alpha handling for each supported hardware path.

### Risks

- Hardware encoders vary by driver and machine.
- Some formats or color modes may need to stay on software encoders.

### Exit Criteria

- Export can use hardware encoding when supported.
- Benchmark shows meaningful export speedup on at least one target machine.

## Phase 3: Cache Improvements

### Objectives

- Avoid recomputing expensive static work across runs and across preview/export paths.

### Work

- Expand `RenderDiskCache` usage for static page chunks and flashing overlays.
- Improve cache keys so preview/export reuse is possible where representation allows it.
- Separate caches for:
  - static layers
  - flashing text overlays
  - tape-independent composited intermediates
- Add cache metrics: hit rate, read/write time, invalidation cause.

### Risks

- Cache invalidation errors can cause subtle visual bugs.
- Larger cache footprint on disk.

### Exit Criteria

- Repeated renders of unchanged projects are measurably faster.
- Cache correctness is verified with style and content changes.

## Phase 4: Draft And Preview Modes

### Objectives

- Improve interactivity without compromising final delivery quality.

### Work

- Add explicit preview quality levels:
  - full quality
  - balanced
  - draft
- Possible draft-mode changes:
  - lower-resolution offscreen materialization
  - reduced blur quality
  - reduced image resampling cost
  - delayed flashing overlay regeneration
- Keep final export on full-quality paths only.

### Exit Criteria

- Styling and playback remain responsive on large projects.
- Quality/performance tradeoffs are explicit and user-controlled.

## Phase 5: Rendering Pipeline Cleanup

### Objectives

- Remove avoidable CPU overhead before attempting a GPU backend.

### Work

- Audit redundant conversions between canvas and writer representations.
- Reduce unnecessary readback/copy steps in tape and overlay composition.
- Reuse temporary buffers more aggressively.
- Identify operations that can be fused instead of layered through multiple bitmaps.

### Exit Criteria

- Less allocation churn.
- Lower per-frame CPU cost in profiling.

## Phase 6: GPU Feasibility Prototype

### Objectives

- Determine whether a GPU-backed Skia path is worth the added complexity.

### Work

- Prototype GPU-backed `Canvas` surfaces for a narrow subset:
  - text
  - gradients
  - blur
  - image compositing
- Measure gain versus cost of readback to CPU memory for FFmpeg.
- Evaluate platform backend choices for Windows first.
- Do this only after Phase 1 data and CPU-side throughput work indicate that per-frame composition still dominates.

### Open Questions

- Does GPU raster still help once per-frame readback is included?
- Which effects still force CPU fallback?
- Can preview benefit even if export does not?

### Exit Criteria

- We have real prototype numbers, not assumptions.
- Decision made: continue, limit to preview, or abandon.

## Phase 7: GPU Mode If Justified

### Objectives

- Add a maintainable GPU-backed mode only if the prototype proves worthwhile.

### Work

- Implement GPU-backed surface lifecycle management.
- Define CPU fallback rules for unsupported operations.
- Keep image parity tests between CPU and GPU modes.
- Expose GPU mode as optional, not default, until proven stable.

### Exit Criteria

- Stable on supported hardware.
- Clear speedup over CPU mode for targeted workloads.

## Suggested Order

1. Phase 0
2. Phase 1
3. Phase 2
4. Phase 3
5. Phase 4
6. Phase 5
7. Phase 6
8. Phase 7 only if Phase 6 justifies it

## Recommended First Deliverable

The first concrete milestone should be:

- instrumentation
- benchmark project set
- finer breakdown of static progressive frame rendering
- one parallel materialization improvement

That should tell us whether the best path is renderer parallelism, cache/pipeline cleanup, or a GPU experiment. Hardware encoding stays useful, but it is no longer the first expected win for the measured workload.
