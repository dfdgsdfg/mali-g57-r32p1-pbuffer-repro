# Native Mali-G57 MC2 r32p1 pbuffer reproduction

A pure Android Java EGL/GLES workload on **Mali-G57 MC2 r32p1** shows rapid
pbuffer graphics/RssFile growth. Flush-only trials were killed after about
25–26 seconds at approximately 1.9 GiB RSS. Inserting a grid fence, flushing,
and immediately deleting the sync completed 600 seconds with zero grid client
polling or retained handles. Four fence policies completed the matched experiment.

- [Report and questions for Arm](REPORT.md)
- [Curated results and source/APK hashes](measurement-summary.json)
- [GPU accounting figure](2026-10-07-gpu-accounting.png)
- [Five-policy isolation figure](2026-10-07-grid-fence-isolation.png)

This tracks observed behavior, not a confirmed driver defect. The exact GPU
allocation and physical retention versus accounting remain unresolved. The
native memory termination does not establish the cause of Flutter #193428's
original readback stall. No Flutter/Impeller runtime is included.

Source revision: 9bd4651ea276d2967424bb605237e2aff32c861c.
The Java/runtime and Gradle inputs are copied byte-for-byte from that revision.
This is the existing tested harness (24 scenarios), not a newly reduced or retested program.
No Flutter/Impeller/native engine .so, credentials, private cloud paths, raw device logs,
APK or third-party JPEG bytes are included. The private tracking repository is not required.

## Build

Use Android SDK 36 and JDK 17 or a newer JDK supported by AGP 9.1.0/Gradle 9.3.1.
Set ANDROID_HOME for your SDK, or create your own local.properties with sdk.dir.
The original JPEGs are from the public repository recorded in jpeg-assets.json;
fetch_jpegs.py downloads the pinned originals and verifies every SHA-256.
Third-party assets retain their original ownership and usage terms.

    python3 fetch_jpegs.py
    chmod +x gradlew
    ./gradlew :app:assembleRelease

## Dedicated-device reproduction

The flush-only control previously triggered Android low-memory termination, including
with oom_score_adj 0. Use a dedicated test device. Replace SERIAL with its adb serial.
Install the release APK (signed using the builder's local Android debug key):

    adb -s SERIAL install -r app/build/outputs/apk/release/app-release.apk

Start each case in a fresh process; capture logcat externally during the run.
Scenario 23 inserts/flushes/immediately deletes a grid fence; scenario 24 flushes only.
Both keep the shared source producer fences and consumer server waits unchanged.

    adb -s SERIAL shell am force-stop dev.repro.nativegles
    adb -s SERIAL shell am start -n dev.repro.nativegles/.MainActivity --ei scenario 23 --ei duration_seconds 600

Wait for the terminal NATIVE_RESULT before the second case:

    adb -s SERIAL shell am force-stop dev.repro.nativegles
    adb -s SERIAL shell am start -n dev.repro.nativegles/.MainActivity --ei scenario 24 --ei duration_seconds 600

Keep device orientation/display dimensions stable. The measured raster pbuffer was
720x1439 and is sized from the Activity's TextureView; other displays can differ.
Log tags: NATIVE_HEARTBEAT, NATIVE_MEMORY_MAPS, NATIVE_GPU_MEMORY, NATIVE_RESULT.
Check app PID against lowmemorykiller/crash/ANR records; platform success alone is insufficient.
The APK has no network permission. JPEGs are packaged at build time.

Additional scenarios: 18 immediate deletion; 19 retain/no polling; 20 poll/retain;
21 poll/delete; 22 flush-only. These use the earlier telemetry without extra GPU/RSS probes.
REPORT.md explains the workload, results, correctness checks and observation limits.
The new package itself has not been run on another physical device. A rebuild has a
new APK hash/signature and should be recorded separately from the historical tested APK.
