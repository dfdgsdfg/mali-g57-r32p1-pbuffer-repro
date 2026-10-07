# Mali-G57 MC2 r32p1: pbuffer graphics memory growth prevented by an immediately deleted fence

Posted to [Arm Community](https://community.arm.com/forums/f/mobile-graphics-and-gaming-forum/57880/mali-g57-mc2-r32p1-pbuffer-graphics-memory-growth-prevented-by-an-immediately-deleted-fence) on 2026-10-07.

Public reproduction: https://github.com/dfdgsdfg/mali-g57-r32p1-pbuffer-repro

The published text below was read back and matched against the prepared body. The full measurement report is in [REPORT.md](REPORT.md).

---

Hello Arm graphics team,

We have a pure Android Java EGL14/GLES30 reproduction on moto g 5G (2022), Android 13/API 33, Mali-G57 MC2. Exact driver: OpenGL ES 3.2 v1.r32p1-01eac0.0a0b917d9265b4c01be9925306306139.

After pbuffer grid draws, glFlush alone leads to rapid graphics memory growth and Android low-memory termination after about 25-26 seconds. Adding glFenceSync before that same flush, then immediately calling glDeleteSync, completes 600 seconds with zero grid client waits and zero retained grid sync handles. Immediate deletion does not establish observed GPU completion.

Public source, build/run instructions, source/APK hashes, detailed measurements and graphs:
https://github.com/dfdgsdfg/mali-g57-r32p1-pbuffer-repro

The workload has two shared ES3 contexts on IO/raster threads, 24 resident 1200x1200 JPEG textures decoded with Android BitmapFactory, full source readbacks and repeated 24x24 mipmapped crop/read/delete cycles. A 4x6 grid is drawn into a 720x1439 pbuffer. Source uploads use producer fences/flushes; raster waits on each source fence before use. Source textures remain immutable and resident. These source synchronization operations are identical in all cases.

Both cases have swap interval zero, pbuffer eglSwapBuffers and a next-grid interval of at least 16 ms after swap returns. Neither adds a CPU wait for grid GPU completion, an outstanding-grid-frame cap or measured-run glFinish. We understand that pbuffer swap does not imply presentation/completion. No Flutter, Impeller or third-party runtime is included.

In the five-policy test, all four fence variants completed 600 seconds: immediate deletion without polling; retention without polling; zero-timeout polling with retention; and polling with deletion. Flush-only was killed at +26.129 seconds from the first heartbeat. Retaining about 30,000 handles adds a separate CPU allocation cost, rather than the rapid graphics growth.

A second matched pair with identical extra memory telemetry repeated the result:
- Immediate deletion: 600 seconds, 85,877 full reads and 30,660 grid frames; last RSS 520.0 MiB, RssFile 327.3 MiB, Android graphics 252.3 MiB.
- Flush-only: last heartbeat 24 seconds, 3,244 reads and 1,056 grids; LMK at +25.046 seconds. Last RSS 1,851.7 MiB, RssFile 1,820.2 MiB, graphics 1,784.4 MiB. The kill record reports 1,932,796 kB RSS.

Before/after the final flush-only smaps scan, process RSS was 1,792,448/1,890,900 kB, while summed CPU smaps RSS was only 216,176 kB. Public Motorola r32p1 kernel code accounts nonmapped GPU pages in MM_FILEPAGES/RSS, consistent with this discrepancy; its exact match to installed firmware is unverified. Direct Mali GPU-page telemetry is permission denied. We have not identified the allocation class or distinguished actual physical retention from an accounting defect.

Questions for Arm:

1. Is this known in r32p1, and is there an erratum/fixed version or OEM reference?
2. Why does an immediately deleted fence change memory behavior when flush alone does not? Does it change render-pass submission or resource retirement?
3. Is an explicit fence boundary required for this offscreen workload, and what mitigation is supported?
4. Which counters/captures can identify the accumulating GPU allocation on a retail non-root device?

These are single physical trials per policy plus the repeated pair. Telemetry can perturb timing, and there is no independent grid pixel oracle. Readbacks/crops continue until LMK; this is memory termination rather than a retained readback-call hang. We do not claim a confirmed permanent leak or specification violation, or a shared cause with Flutter #193428's original stall or the legacy texture-name erratum.
