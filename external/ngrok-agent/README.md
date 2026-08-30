# DAMN Ngrok Agent

Custom embedded agent built on the official `ngrok-go` library.

## Building `libngrok.so` for `jniLibs/arm64-v8a`

Requires Go 1.21+ and NDK r27c.

```powershell
# 1. Cross-compile for Android arm64 (bionic linkage required for DNS)
$env:GOOS="android"
$env:GOARCH="arm64"
$env:CGO_ENABLED="1"
$env:CC="C:\Users\BinBash\AppData\Local\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd"
go build -trimpath -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" -o libngrok.so .

# 2. Place
Copy-Item libngrok.so "app\src\main\jniLibs\arm64-v8a\libngrok.so" -Force
```

## Protocol
The agent speaks to `NgrokManager.kt` via stdout:
- `TUNNEL_URL <url>`: Published once the tunnel is ready.
- `AGENT_ERROR <msg>`: Published on fatal errors.
- Other lines are treated as progress/log chatter.
