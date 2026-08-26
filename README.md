# D·A·M·N — Drop Any Media Now

Android app that hosts a **file or folder** through a simple **PHP web server** (`php -S` style) on a **custom port chosen by the user**. The port is automatically **forwarded via NAT / UPnP IGD** and the app can **run in background** as a foreground service with an optional **Start at Boot**.

### Features
- 📁 Pick any file **or** folder via Storage Access Framework (SAF)
- 🐘 PHP-like server: `http://0.0.0.0:{port}` – serves static files, directory listing (like `php -S`), and simple PHP rendering (`echo`, `print`, `phpinfo()`, variables, `date()`)
- 🔀 Custom port (1024–65535) with validation
- 🌐 NAT port forwarding via **UPnP IGD** (SSDP M-SEARCH → SOAP `AddPortMapping`/`DeletePortMapping` + `GetExternalIPAddress`). Shows public URL `http://{externalIp}:{port}/`
- 🔔 Runs in background as **Foreground Service** with persistent notification (Start/Stop)
- 🚀 **Start at Boot** option (`RECEIVE_BOOT_COMPLETED` + `BootReceiver`) – auto-starts if enabled
- 📋 Local & public URL display with copy buttons, live logs

### Tech
- Kotlin, AndroidX, Material3, `ServerSocket` + thread pool, custom UPnP client, `SharedPreferences`, SAF

### Project Structure
```
app/src/main/java/com/damn/app/
  MainActivity.kt
  util/Prefs.kt, FileUtils.kt
  server/PhpFileServer.kt, NatPortMapper.kt
  service/ServerService.kt
  receiver/BootReceiver.kt
res/layout/activity_main.xml
```

### Permissions
`INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`

### Build
```bash
./gradlew assembleDebug
# or
gradle assembleDebug
```
Requires Android SDK 35, JDK 17+, Gradle 8.7+.

### How it works
1. User picks file/folder – copied to `cache/damn_host` for serving (persists across SAF permission).
2. `PhpFileServer` (port-sensitive `ServerSocket`) serves on all interfaces. Directory listing mimics `php -S`.
3. If NAT enabled, `NatPortMapper` does SSDP discovery for IGD, fetches `controlURL`, sends `AddPortMapping` for TCP `port -> localIp:port`, then queries external IP.
4. `ServerService` wraps server as foreground service; `BootReceiver` re-starts on boot if toggled.

### Limitations
- PHP engine is lightweight (no full interpreter); for full PHP, bundle a native `php` binary and exec `php -S`.
- UPnP requires router support; otherwise manual forwarding needed.
- Android 13+ needs notification permission for foreground service.

### Branch
`v1` – initial release.

