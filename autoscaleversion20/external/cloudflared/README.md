# cloudflared for Android (DAMN)

Free Cloudflare Tunnel — `https://*.trycloudflare.com` quick tunnel (no account) or named tunnel via token.

## Quick tunnel (free, no token)
On device, enable **CF** switch in DAMN main screen, leave token blank in Settings → Cloudflare. The app runs:
```
cloudflared tunnel --no-autoupdate --url http://127.0.0.1:8080
```
and parses `https://xxxx.trycloudflare.com` from stdout.

## Named tunnel (free with Cloudflare account)
1. Dashboard → Zero Trust → Networks → Tunnels → Create → copy token (`cloudflared tunnel token <name>`)
2. Paste token in DAMN Settings → Cloudflare → Tunnel Token → Save
3. Enable **CF**

## Building `libcloudflared.so` for `jniLibs/arm64-v8a`

Requires Go 1.21+ and NDK r27c.

```powershell
# 1. Fetch cloudflared source
git clone https://github.com/cloudflare/cloudflared.git
cd cloudflared

# 2. Cross-compile for Android arm64 (bionic linkage required for DNS, like libngrok.so)
$env:GOOS="android"
$env:GOARCH="arm64"
$env:CGO_ENABLED="1"
$env:CC="C:\Users\BinBash\AppData\Local\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd"
go build -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" -o libcloudflared.so ./cmd/cloudflared

# 3. Place
Copy-Item libcloudflared.so "C:\Users\BinBash\Documents\01DAMN\videochat\app\src\main\jniLibs\arm64-v8a\libcloudflared.so" -Force
```

APK will build without the binary (manager shows `missing from native library directory` error), but tunnel will only work once `libcloudflared.so` is present.

Size: ~35 MB stripped (vs libngrok.so 11 MB).

## Notes
- cloudflared is Apache-2.0
- Quick tunnels are ephemeral, URL changes each Start
- The app sets `HOME=<filesDir>` for config/cache
