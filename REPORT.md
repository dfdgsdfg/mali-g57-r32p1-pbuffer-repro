# Mali-G57 MC2 r32p1: pbuffer graphics memory growth prevented by an immediately deleted fence

Hello Arm graphics team,

We have isolated rapid memory growth in a standalone Android EGL/OpenGL ES 3 workload on Mali-G57 MC2. With only `glFlush` after each pbuffer grid draw, Android kills the process after approximately 25–26 seconds at about 1.9 GiB RSS. Adding `glFenceSync` before the same flush, followed by immediate `glDeleteSync`, allows the workload to complete 600 seconds. This variant performs **zero grid client waits and retains zero grid sync handles**.

The app uses Android Java EGL14/GLES30 directly, with no Flutter, Impeller, Skia engine, plugins or third-party runtime. We are asking whether this is a known driver behavior/defect and how to identify the accumulating allocation. We have not established a permanent physical leak or a specification violation.

## Device and controlled workload

- Physical moto g 5G (2022), Android 13/API 33, Firebase Test Lab device `austin`.
- Renderer: `Mali-G57 MC2`.
- Exact version: `OpenGL ES 3.2 v1.r32p1-01eac0.0a0b917d9265b4c01be9925306306139`.
- Two shared ES 3 contexts on IO and raster Java threads. EGL config supports `WINDOW_BIT | PBUFFER_BIT`, RGBA8. IO uses a 1×1 pbuffer; raster uses a 720×1439 pbuffer.
- 24 resident 1200×1200 JPEG sources, decoded with Android BitmapFactory and uploaded lazily using mutable `glTexImage2D(null)` → `glTexSubImage2D` → mip generation → per-source fence → producer flush → CPU publication. Source textures/fences stay resident and immutable during measurement, without eviction or reupload.
- Every raster use waits on the relevant source fence with `glWaitSync`; this cross-context ordering is unchanged in all policies.
- IO repeatedly reads a full 1200×1200 source through a temporary FBO. Raster draws an origin-half crop into a newly allocated 24×24 RGBA output, generates its mips, reads it, then deletes the crop texture/FBO. Handoffs are bounded to eight; each resident source is handed off four times consecutively in a 24-source ring.
- Raster also draws the resident sources as a 4×6 grid into the pbuffer's default framebuffer. The next grid starts at least 16 ms after the previous swap returns; EGL swap interval is zero. There is no CPU wait for grid GPU completion or limit on outstanding grid frames in the immediate-delete and flush-only cases. No resize or window lifecycle churn occurs.

The matched difference is only the grid tail:

```c
// Failure control, after the grid draws:
glFlush();
eglSwapBuffers(display, pbuffer);

// Immediate-delete fence variant, after the same grid draws:
GLsync sync = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
glFlush();
glDeleteSync(sync);  // No glClientWaitSync, no retained grid handle.
eglSwapBuffers(display, pbuffer);
```

Neither case adds measured-run `glFinish` or a blocking client wait. Final cleanup uses `glFinish` only after terminal measurement. We understand that `eglSwapBuffers` has no effect on a pbuffer and does not establish GPU completion; immediate sync deletion also does not establish observed completion.

## Physical results

Five matched policies from one APK:

| Grid policy | Actual app progress | Full reads | Grid frames | Grid client waits | Retained grid handles at last sample |
|---|---:|---:|---:|---:|---:|
| Fence, flush, immediate deletion; no polling | 600 s, completed | 86,614 | 30,671 | 0 | 0 |
| Fence, flush, retain all handles; no polling | 600 s, completed | 92,888 | 30,935 | 0 | 30,935 |
| Fence, flush, zero-timeout polling; retain signaled handles | 600 s, completed | 86,948 | 30,708 | 61,404 | 30,708 |
| Fence, flush, zero-timeout polling; delete signaled handles | 600 s, completed | 86,721 | 30,725 | 61,443 | 1 |
| Flush only, no grid fence | Last heartbeat 25 s; LMK at +26.129 s | 3,248 | 1,088 | 0 | 0 |

All four fence cases emitted terminal results matching their game-loop JSON and kept IO/crop/grid counters advancing through 600 seconds. Their final Android graphics summary was approximately 244–253 MiB. Retaining approximately 30,000 handles added a separate 65–80 MiB CPU-mapped RSS cost compared with deleting them; it did not cause the rapid graphics growth.

A second matched pair added identical RssFile/RssAnon/RssShmem sampling and process-status reads before/after each smaps scan:

| Last sampled metric | Immediate fence deletion | Flush only |
|---|---:|---:|
| App progress | 600 s, completed | Last heartbeat 24 s; LMK at +25.046 s |
| Full reads / crops / grid frames | 85,877 / 85,870 / 30,660 | 3,244 / 3,237 / 1,056 |
| Grid client waits / retained handles | 0 / 0 | 0 / 0 |
| Process RSS | 520.0 MiB | 1,851.7 MiB |
| RssFile | 327.3 MiB | 1,820.2 MiB |
| RssAnon | 191.3 MiB | 30.3 MiB |
| Android graphics summary | 252.3 MiB | 1,784.4 MiB |

The failing process's PID matches the Android LMK record:

```text
lowmemorykiller: Kill 'dev.repro.nativegles' (23999), uid 10415, oom_score_adj 0 to free 1932796kB rss, 228324kB swap; reason: PSI critical event
```

The intervals above are measured from the first app heartbeat to the LMK log timestamp. Work counters continue advancing until the kill; these failing runs show memory termination rather than a retained readback-call hang. Test Lab reports SUCCESS even for the killed game loop, but it has no terminal app result and an empty game-loop JSON; we classify it as incomplete.

## Memory accounting evidence and limits

In the second flush-only run, the last smaps scan ended at 21.750 s and took 1.591 s. Process RSS was 1,792,448 kB before and 1,890,900 kB after the scan, while summed CPU smaps RSS was only 216,176 kB. RssFile was 1,625,528/1,726,100 kB on either side. Thus scan timing alone cannot explain the large discrepancy. These measurements are distinct accountings and are not additive.

Public Motorola r32p1 kernel source contains a mechanism consistent with this result: [kbasep_os_process_page_usage_update](https://github.com/MotorolaMobilityLLC/kernel-mtk/blob/4cbe43fe20dc19b9c5218553468efb3b868567c5/drivers/gpu/mediatek/gpu_mali/mali_valhall/mali-r32p1/drivers/gpu/arm/midgard/mali_kbase_mem_linux.c#L3214) accounts nonmapped GPU pages in the owning process's `MM_FILEPAGES`/RSS. The [allocation helper](https://github.com/MotorolaMobilityLLC/kernel-mtk/blob/4cbe43fe20dc19b9c5218553468efb3b868567c5/drivers/gpu/mediatek/gpu_mali/mali_valhall/mali-r32p1/drivers/gpu/arm/midgard/mali_kbase_mem.c#L2170) describes making allocations visible to the OOM killer. We have **not verified that this source revision exactly matches the installed firmware**.

Reading `/proc/mtk_mali/gpu_memory` is denied with EACCES on this retail device. We cannot yet distinguish tiler/transient render/job allocations from another pool, or retained physical pages from an accounting defect. The fence case does not have zero overall allocation growth: after its initial ≥10 s sample, graphics changes by approximately −2 MiB, while RSS rises about 36 MiB over the remaining run.

There is one physical run per policy plus the second immediate-delete/flush-only pair. Telemetry may perturb timing. Full source reads check 18 CPU-decoded pixel locations; crops compare repeat bytes to the first GPU output. There is no independent automated grid pixel oracle. The same follow-up APK completed both policies for 75 seconds on local ANGLE/SwiftShader, which is a harness check rather than a second physical-GPU control.

## Questions for Arm

1. Is rapid pbuffer graphics/RssFile growth with this command sequence known in r32p1? Is there an erratum, fixed driver version or OEM reference?
2. Why would inserting a fence, then flushing and immediately deleting it without any client polling, change memory behavior when `glFlush` alone does not? Does it change render-pass submission or resource retirement?
3. Is an explicit fence boundary expected or required for this offscreen workload? If so, what is the supported synchronization/submission contract?
4. Which allocation counters or captures can identify the accumulating resource on a non-root production device, and distinguish actual retained GPU pages from RSS accounting?
5. Is the immediate-delete fence sequence an appropriate mitigation, or should we use a different supported offscreen rendering pattern?

The public reproduction repository is [dfdgsdfg/mali-g57-r32p1-pbuffer-repro](https://github.com/dfdgsdfg/mali-g57-r32p1-pbuffer-repro). It contains native source, build/run instructions, exact JPEG provenance and a hash-checked download script, a curated measurement summary and two figures. The follow-up source revision is `9bd4651ea276d2967424bb605237e2aff32c861c`; the tested APK SHA-256 is `111dadfd8cf481031eb85f0c06030a8010447a59d339551fd5d8b9fc4a97b89f`. Rebuilds need not be byte-identical to that APK.

This was found while reducing [Flutter #193428](https://github.com/flutter/flutter/issues/193428), but the native memory termination above does not reproduce its original retained readback stall, and we have not established a shared cause. We also do not assume a relationship with the legacy texture-name erratum EN_ID 1,792,661 or the separate Mali-G76 r38p1 retention report.
