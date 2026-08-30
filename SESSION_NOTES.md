# DAMN Development Session Notes — 2026-08-27

## Current State (v17.0.0)

### 1. Release Signing [SUCCESS]
- Configured `keystore.properties` in project root (gitignored).
- Updated `app/build.gradle.kts` to load production key (`key0`) automatically.
- Build command: `.\gradlew.bat bundleRelease` now generates a signed AAB.

### 2. UI & Settings [FIXED]
- **Toggles Default**: Updated `Prefs.kt` so NAT, Tor, Ngrok, and Cloudflare are **OFF by default**.
- **Settings Tabs**: Changed to `scrollable` and disabled All Caps to prevent text wrapping on smaller/high-density screens (like S24+).
- **Portrait Lock & Custom DNS**: Added to **General** tab and verified saving logic.
- **Header Padding**: Reduced `paddingTop` in `activity_main.xml` for better consistency.

### 3. Android 15 Compatibility [DONE]
- **Foreground Service**: Implemented notification-based start for Android 15+ boot sequence.
- **16 KB Page Size**: Verified and re-compiled native libraries with 16 KB alignment.
- **Ngrok**: Integrated custom bionic-linked agent to fix DNS issues.

## Next Steps for v17.0.1
1. Monitor Play Store crash reports for Android 15 background starts.
2. Verify Cloudflare tunnel stability on S24+ with new GOMAXPROCS limits.
3. Consider full Edge-to-Edge migration for UI.
