# D·A·M·N — Drop Any Media Now

D·A·M·N is an anonymous file sharing Android app that uses local network (with UPnP), Tor, or Ngrok to share any file or folder with anyone.

### Features
- 📁 Pick any file **or** folder via Storage Access Framework (SAF)
- 🐘 PHP-based server: Serves static files, directory listings, and simple PHP rendering.
- 🧅 **Tor Integration**: Automatically generates a `.onion` address for anonymous sharing.
- 🔌 **Ngrok Integration**: Creates a secure tunnel for public access without router configuration.
- 🔀 Custom port (1024–65535) with validation.
- 🌐 NAT port forwarding via **UPnP IGD**. Shows public URL `http://{externalIp}:{port}/`.
- 🔔 Runs in background as **Foreground Service** with persistent notification.
- 🚀 **Start at Boot** option – auto-starts the server when your device boots.
- 📋 URL display with copy buttons and live logs.

### Tech Stack
- Kotlin, AndroidX, Material3
- `ServerSocket` + thread pool
- Tor (via Guardian Project's libraries)
- Ngrok (via native binary tunnel)
- Custom UPnP client for NAT traversal

### Project Structure
```
app/src/main/java/com/damn/app/
  MainActivity.kt
  util/        - Preferences and file utilities
  server/      - Server engines (PHP, Tor, Ngrok, NAT/UPnP)
  service/     - ServerService for background operation
  receiver/    - BootReceiver for auto-start
res/layout/    - UI Layouts
```

### Permissions
`INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`

### How it works
1. **Selection**: User picks a file or folder via SAF.
2. **Serving**: `PhpFileServer` starts on the chosen port.
3. **Public Access**: 
    - **NAT**: `NatPortMapper` uses UPnP to open a port on the router.
    - **Tor**: `TorManager` starts a hidden service and provides an onion address.
    - **Ngrok**: `NgrokManager` starts a tunnel to the local server.
4. **Persistence**: `ServerService` ensures the server stays alive in the background.

### Build Instructions
```bash
./gradlew assembleDebug
```
Requires Android SDK 35+, JDK 17+, and Gradle 8.x.
