# Release preparation

The `stableRelease` variant enables R8 minification and resource shrinking. It
removes verbose Kotlin and native Link timing diagnostics, retains WARN/ERROR logs,
and excludes debug-only profileability and launch diagnostics. Link JNI entry points
are retained by `app/proguard-rules.pro`.

The local release command signs the APK and copies it to a file whose name includes
the application version:

```powershell
.\scripts\build-release-local.ps1
```

The command reads the existing local signing identity below. Run it from the same
Windows account that created the protected password. For an offline build, pass
`-Offline`. The result is currently
`app/build/outputs/apk/stable/release/push-reel-stable-release-v0.1.0.apk`.

For other build environments, set all four environment variables in the build process:

| Variable | Meaning |
| --- | --- |
| `PUSHREEL_RELEASE_STORE_FILE` | Absolute path to the existing release keystore |
| `PUSHREEL_RELEASE_STORE_PASSWORD` | Keystore password |
| `PUSHREEL_RELEASE_KEY_ALIAS` | Signing key alias |
| `PUSHREEL_RELEASE_KEY_PASSWORD` | Signing key password |

Then run `./gradlew.bat :app:assembleStableRelease`. Do not commit the keystore or
passwords. If the variables are absent, Gradle produces an **unsigned** release APK
for build verification only.

For the local direct-install APK, the signing identity was created on 2026-10-02 at
`%LOCALAPPDATA%\PushReel\signing\pushreel-release.p12`, with alias `pushreel`.
Its password is stored at `release-password.dpapi` in the same directory, encrypted
for the current Windows account. Reuse this identity for every update. Back up the
keystore **and a recoverable password** securely before relying on it for future
releases: the DPAPI file alone cannot be decrypted after losing this Windows account.
Never copy either file into the Git repository or publish the password.

Gradle's original output is
`app/build/outputs/apk/stable/release/app-stable-release.apk`; the project command
uses the generated APK metadata to create the versioned copy only after checking
its Android signature against the PushReel release certificate.
The first signed `0.1.0` APK was built on 2026-10-02; R8, resource shrinking,
vital lint, and Android APK signature verification passed. Its signer certificate
SHA-256 is `d4a19fddf1001c9d26a3669301ac62d69f2b9d99fd0aa9d7b2bde5c10aaeae5f`.
It can be copied to the phone and opened with Android's package installer; USB
debugging is not required. The installed debug build uses Android's debug signing
key, so it cannot be updated in place with this release APK. Uninstall the debug
app from the primary profile first, then install the release APK. This resets
app-local settings, including the saved Photo/Video mode; completed Gallery media
in `DCIM/Camera` is separate from app data. The user controls all phone UI actions.

A successful build and signature verification do not establish that R8/JNI,
CameraX, Link discovery, recording, and Gallery publishing work on a phone.

Before wider distribution, review third-party license notices and install and test
the signed release on the physical phone. Direct installation uses APK; Google Play
publication would need a separate AAB decision. The first local release has
versionCode `1` and versionName `0.1.0`; increment versionCode for subsequent
releases. The debug owner-only installation script is for debug builds and must
not be used for release.
