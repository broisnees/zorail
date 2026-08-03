# Zorail on Android

Run the real Zorail server — inbound SMTP, JSON API, SQLite — inside an Android
app with a native Jetpack Compose UI, and receive mail on the handset.

The mail engine is the Go core compiled to a real arm64 `.so`; the UI is Compose
talking to that server over loopback. An earlier pass rendered the bundled web
dashboard in a WebView instead — it worked, but that dashboard is a desktop
layout, and on a phone it spent a sixth of the width on a sidebar rail, wrapped
its own labels, and hid the message reader entirely below 900px. Reuse is worth a
lot; it is not worth shipping a UI that feels borrowed.

## Is this doable?

The server part: yes, and it is already working (`go test ./mobile/`). The Go
core cross-compiles to `android/arm64` unchanged, because Zorail uses
`modernc.org/sqlite` — a pure-Go SQLite with no CGO. That is the single fact that
makes this cheap instead of a rewrite.

The part that does **not** work is pointing an MX record at your phone. Four
independent reasons, any one of which is fatal:

| Obstacle | Why it kills phone-as-MX |
| --- | --- |
| Carrier CGNAT | Mobile data gives you a shared, non-routable address. Nothing outside can open a connection to you. |
| Inbound port 25 | Blocked by essentially every mobile carrier and residential ISP. |
| Rotating IP | MX needs a stable address. Yours changes on every reconnect and every cell handover. |
| No PTR / rDNS | Sending mailservers reject or spam-file mail from hosts without matching forward and reverse DNS. Consumer IP ranges are on blocklists by default. |

So the phone can be the **mailstore**, but something with a stable public
address has to be the **MX**. Below is how to bridge that, using code Zorail
already has.

## Architectures

### A. Local / LAN only — works today, no extra infrastructure

Set `bindPublic = true` and the phone accepts SMTP on `<phone-ip>:2525` from any
device on the same network. Good for testing an app's signup flow against a
handset, or for devices on your LAN that emit mail (printers, NAS, CI runners).

Add Tailscale and this extends to any of your own machines anywhere, still with
no public exposure.

**Not reachable from the public internet.** That is the point.

### B. Cloudflare Email Routing → tunnel → phone (recommended)

This is the one that gets you real mail from real senders, and it needs **zero
new server code** — `POST /api/ingest` already accepts raw `message/rfc822` with
recipients in `?rcpt=`, which is exactly what a Cloudflare Email Worker can send.

```
sender → MX (Cloudflare, stable + reputable)
       → Email Routing catch-all
       → Email Worker
       → POST https://<tunnel-host>/api/ingest?rcpt=… (Bearer <apiToken>)
       → phone stores, indexes, notifies
```

The phone's connection to the tunnel is **outbound**, so CGNAT is irrelevant.
Zorail's existing `zorail setup` (`internal/cfsetup`) already automates the
Cloudflare Email Routing side; the only new piece is running
`cloudflared`/Tailscale Funnel on the device and pointing the Worker at it.

Trade-off: mail arrives only while the phone is online. Cloudflare retries, but
give it a fallback — see C.

### C. B, plus a VPS as the durable spool

Keep a small Zorail instance on a VPS as the authoritative store, and have the
phone mirror from it. Mail is never lost when the phone is off, and the handset
holds a full local copy for offline reading.

This needs one thing Zorail does not have yet: a cursor endpoint
(`GET /api/messages?since=…`) so the phone can pull only what it has not seen.
The long-poll `GET /api/inboxes/{inbox}/wait` covers a single known inbox but not
"everything new across all inboxes". That endpoint is the next piece of work if
you want C.

**Recommendation:** ship A (it already works, and it is genuinely useful for
testing), then B. Treat C as the follow-up once you want durability.

## Why Go + gomobile rather than a Kotlin rewrite

You already own a tested SMTP ingest path, MIME parser, storage layer with FTS5,
retention sweeper, forwarding worker, AI extraction, and an embedded web UI.
Rewriting that in Kotlin means reimplementing all of it and then maintaining two
implementations that must agree on parsing edge cases.

| Approach | Verdict |
| --- | --- |
| **Go core via `gomobile bind` + native Compose UI** | What this ships. The mail engine is reused wholesale; only the UI is written twice, and a phone UI genuinely has to be different anyway. |
| Pure Kotlin (Apache James / SubEthaSMTP + Ktor) | Full rewrite of everything above. Slower to build and permanently divergent. Performance is not the differentiator — a phone's mail volume is trivial either way. |
| Rust + UniFFI | Also a rewrite. Faster than needed for this workload. |
| Termux running the existing `zorail` binary | Zero effort, and the right way to *validate the idea tonight* — `pkg install golang`, `make build`, run it. Not shippable to anyone else. |

Performance is a non-issue here: the bottleneck is the radio and the OS
scheduler, not MIME parsing. Pick on maintenance cost, which points at reuse.

## Android constraints that shape the design

These are not incidental — each one is why a piece of the code looks the way it
does.

- **Ports below 1024 need root.** Hence SMTP on 2525. Anything expecting port 25
  needs a port mapping upstream.
- **A plain thread gets killed.** The server runs in a foreground service with an
  ongoing notification. On API 34+ that requires
  `foregroundServiceType="dataSync"` *and* the matching
  `FOREGROUND_SERVICE_DATA_SYNC` permission.
- **Doze suspends network access** when the screen is off. Ask the user for a
  battery-optimisation exemption or the listener goes deaf while idle. A
  `WIFI_MODE_FULL_LOW_LATENCY` Wi-Fi lock keeps the radio associated.
- **Scoped storage.** SQLite must live under `context.filesDir`; the Go side has
  no default that works, so `DBPath` is a required option.
- **`POST_NOTIFICATIONS` is a runtime permission** on API 33+. Denial only costs
  notifications — mail is still received and stored.
- **The OS can still stop you.** Low memory, aggressive OEM battery managers
  (Xiaomi, Samsung, Huawei are the usual offenders), or a user swipe. Treat the
  server as restartable; never assume uptime.
- **gomobile's type system is narrow.** Only numeric types, `string`, `bool`,
  `[]byte`, `error`, and exported structs/interfaces from the bound package
  cross the boundary. No maps, no `[]string`, no `context.Context`, no
  `time.Time`. That is why `Options.AllowedDomains` is a comma-separated string
  and why `mobile` is a facade rather than a direct binding of `internal/`.
- **Never block in a bound call.** A gomobile method blocks the calling Java
  thread, so `Start` binds and returns; failures arrive on `Listener.OnError`.

## Build

### One-time toolchain setup

Install the SDK command-line tools, then get the NDK **through `sdkmanager`**:

```sh
brew install --cask android-commandlinetools
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
sdkmanager --install "ndk;28.2.13676358" "platforms;android-35" "build-tools;35.0.0"
make android-tools    # installs gomobile + gobind, runs gomobile init
```

Do **not** use the `android-ndk` Homebrew cask on macOS. It ships the NDK inside
an `AndroidNDK*.app` bundle that arrives quarantined, so its `clang` cannot
execute — `gomobile bind` fails with an opaque `cgo: exit status 2`, and opening
the bundle in Finder gets you *"is damaged and can't be opened"*. That dialog is
Gatekeeper, not corruption; **do not click "Move to Trash"**. Clearing it needs
`sudo xattr -dr com.apple.quarantine <bundle>`, and once Gatekeeper has failed
verification the bundle can become unreadable outright. `sdkmanager` sidesteps
all of it — plain directories under `$ANDROID_HOME/ndk/`, never quarantined.

Three more things that bite:

- **`gomobile bind` needs `-androidapi`.** It defaults to API 16, which is below
  the supported 21..35 range, so the build dies before compiling anything. The
  Makefile pins 26 to match `minSdk`.
- **`golang.org/x/mobile` must be a module dependency**, not just an installed
  binary: `go get -tool golang.org/x/mobile/cmd/gobind`. Hence the `tool`
  directive in `go.mod`.
- **JDK version.** AGP 8.7 wants JDK 17–21. If your default `java` is newer, set
  `org.gradle.java.home` in `android/gradle.properties`, and use the Gradle
  wrapper (8.9) rather than a system Gradle 9.x, which AGP 8.7 rejects.

### Performance: build release, not debug

A debug Compose build is not representative and feels bad on a 120Hz screen.
Measured on a Nothing Phone (1), same code, `adb shell dumpsys gfxinfo`:

| | debug | release |
| --- | --- | --- |
| Janky frames | 6.05% | 0.41% |
| 99th percentile frame | 200ms | 12ms |
| 90th percentile frame | 9ms | 7ms |

At 120Hz the budget is 8.3ms per frame, so release clears it at the 90th
percentile while debug misses badly at the tail. Judge the feel on
`assembleRelease` only. The release build here is signed with the debug key so it
stays installable for local testing — replace that before distributing.

To measure:

```sh
adb shell dumpsys gfxinfo ai.zorail.android reset
# ...scroll around...
adb shell dumpsys gfxinfo ai.zorail.android | grep -E "Janky|percentile"
```

### Building

Then:

```sh
make ui            # only if the *web* dashboard changed; the app does not use it
make android-aar   # binds ./mobile → android/app/libs/zorail.aar
make android       # debug APK
make android-install
```

## Testing without a device

`mobile/mobile_test.go` runs the embedded server on loopback and delivers a real
message through it, asserting the arrival callback the notification depends on:

```sh
go test ./mobile/ -v
```

Confirm the core still cross-compiles for the handset:

```sh
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build ./...
```

To try the whole thing before writing any Android code, run the existing binary
under Termux — same code, same behaviour, none of the Gradle setup.

## Layout

```
mobile/mobile.go        gomobile facade: Options, Listener, Server
mobile/mobile_test.go   embedded-server lifecycle + real SMTP delivery
android/app/.../data    OkHttp client for the loopback JSON API
android/app/.../ui      Compose screens: inboxes, messages, reader, domain, settings
android/app/.../*.kt    foreground service, prefs, activity
```

Two additive changes in the core support this: `notify.Hub.SubscribeAll` (arrival
events across all inboxes, for notifications) and `api.Server.Serve(listener)`
(bind-then-serve, so a port conflict fails fast and visibly instead of surfacing
after the UI has already said "running").
