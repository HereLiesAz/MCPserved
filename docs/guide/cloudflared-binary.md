# Bundled cloudflared provenance and integrity

The Android Quick and Named Tunnel modes execute the bundled ARM binaries,
packaged as `libcloudflared.so` (a position-independent executable, **not** a JNI library).
These are rebuilt from Cloudflare's `cloudflared` source at
`733bfb939963e150dcf5c4faddb1603f744fbc98` (release 2026.8.2), with
Android NDK/Bionic, `CGO_ENABLED=1` and `-buildmode=pie`. The exact
two-ABI rebuild commands and original Android DNS/PIE reasons are in
[CloudflareTunnel.kt](../../app/src/main/java/com/hereliesaz/mcpserved/transport/CloudflareTunnel.kt).

## Pinned repository artifacts

| ABI | Git blob SHA-1 of checked-in executable |
| --- | --- |
| arm64-v8a | `b96ce1ff861edb895fd1a87c0d67b31391591efc` |
| armeabi-v7a | `0142aad65724c21a93e83177142708334260a04d` |

The Gradle `verifyCloudflaredBinaries` task streams each file, computes Git's
`SHA1("blob " + byteLength + NUL + content)` and compares it with the pinned
IDs. `preBuild` depends on that task, so APK/AAB builds refuse modified
bundles until a reviewer updates the baseline deliberately. A simple Git blob
hash does **not** demonstrate that the build reproduces upstream source.
These bytes are not Cloudflare-signed release assets, and a fully reproducible
toolchain + independently checked SHA-256 provenance remains future work.

The binary was previously exercised on Android to establish PIE startup;
actual tunnel connectivity and DNS resolution must be checked on device.
Do not infer either from an ELF header, a successful Gradle build, or these hashes.
