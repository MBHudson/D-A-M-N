# NewRelease v17.0.0 – Play Store

This folder is a clean copy of videochat (without web/ and without old APKs).

Version: code 17 / name 17.0.0 (app/build.gradle.kts:14-15)
Included APK/AAB are DEBUG-signed (signingConfig = debug). 
For Play Store update you MUST sign with your EXISTING release keystore:

1. Put your play store .jks/.keystore in this folder (gitignored via *.jks)
2. Create keystore.properties:
   storeFile=release.jks
   storePassword=...
   keyAlias=damn
   keyPassword=...

3. Edit app/build.gradle.kts:
   signingConfigs { create("release") { ... } }
   buildTypes.release.signingConfig = signingConfigs.getByName("release")

4. Build: .\gradlew.bat bundleRelease -> app/build/outputs/bundle/release/app-release.aab

local.properties is present for local builds only and is gitignored (do not commit).
CF fix: CloudflaredManager.kt:78 now --no-autoupdate tunnel --url http://localhost:port with fallback loop + native android libcloudflared.so 25MB (GOOS=android)
