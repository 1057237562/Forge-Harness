# P0 tool provenance

This is a local development probe, not a production distribution bundle. No AIDE,
Termux or Mobile-Harness code is copied into this probe. Tool binaries are downloaded
or read from the developer's installed SDK and ignored by Git. The generated APK
embeds the following components to exercise them directly on Android.

| Component | Version / source | Notice |
|---|---|---|
| Eclipse ECJ | 3.18.0, Maven Central `org.eclipse.jdt:ecj` | EPL-2.0, see embedded `about.html`; source artifact at the same Maven coordinate |
| D8 | 2.1.7-r1, installed Android SDK Build Tools 30.0.3 | Android SDK build-tools NOTICE (Apache-2.0 and third-party notices) |
| apksig | installed Android SDK Build Tools 30.0.3 | Android SDK build-tools NOTICE |
| Android API stubs | installed SDK Platform android-29 | SDK platform distribution; build API fixture only |
| aapt2, zipalign | AndroidIDEOfficial/androidide-tools release v34.0.4, aarch64 archive | Archive `NOTICE.txt`, retained in APK assets; unrelated host libc++ is not packaged |

The native archive is a tool-only distribution: the probe does not install or run
the AndroidIDE terminal, Termux packages, a Linux rootfs, PRoot, JDK or Gradle on
the phone. It packages aapt2/zipalign as executable native APK components and uses
the Android loader with a per-process library path.

The ECJ version is deliberately a Java-8-compatible P0 baseline. It is not the
ECJ 3.39.0 mentioned in the incomplete AIDE source mirror. This probe does not
claim modern Java/Kotlin, AIDE implementation identity or arbitrary project support.

`build.ps1` verifies downloaded archive hashes and writes hashes of all actual
tool inputs to `build/toolchain-manifest.json`. These hashes identify the tested
inputs; the first observed archive hash is not an upstream signature. Production
packaging requires a complete source/license inventory and source-build strategy.

Sources:

- https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.18.0/
- https://github.com/AndroidIDEOfficial/androidide-tools/releases/tag/v34.0.4
- https://r8.googlesource.com/r8/
- https://android.googlesource.com/platform/tools/apksig/
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/

The generated signing key is a development-only key local to `.cache/native-tools`.
Its private key is intentionally embedded in the probe so the phone can sign the
sample APK. It must never be used for a production app or a user's release build.
