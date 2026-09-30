# Release preparation

The `stableRelease` variant enables R8 minification and resource shrinking. It
removes verbose Kotlin and native Link timing diagnostics, retains WARN/ERROR logs,
and excludes debug-only profileability and launch diagnostics. Link JNI entry points
are retained by `app/proguard-rules.pro`.

To sign the release APK, set all four environment variables in the build process:

| Variable | Meaning |
| --- | --- |
| `PUSHREEL_RELEASE_STORE_FILE` | Absolute path to the existing release keystore |
| `PUSHREEL_RELEASE_STORE_PASSWORD` | Keystore password |
| `PUSHREEL_RELEASE_KEY_ALIAS` | Signing key alias |
| `PUSHREEL_RELEASE_KEY_PASSWORD` | Signing key password |

Then run `./gradlew.bat :app:assembleStableRelease`. Do not commit the keystore or
passwords. If the variables are absent, Gradle produces an **unsigned** release APK
for build verification only. A successful build does not establish that R8/JNI,
CameraX, Link discovery, recording, and Gallery publishing work on a phone.

Before distribution, select the signing identity and distribution format (APK or
AAB), set a suitable versionCode/versionName, review third-party license notices,
and install and test a signed release on the physical phone. The debug owner-only
installation script is for debug builds and must not be used for release.
