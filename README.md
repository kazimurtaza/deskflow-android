# Deskflow Android

A **near** fully functional [Deskflow (deskflow.org)](https://deskflow.org) client for Android devices.

> NOTE: Everything here in, assumes you already use Deskflow on your desktop &
> know how to configure a client/screen

## Credits & Fork Status

This project was originally created by **[Jonathan Glanz](https://github.com/jglanz)** as
[`deskflow-android`](https://github.com/jglanz/deskflow-android) (© 2025 Jonathan Glanz,
released under the MIT License). **Many thanks to Jonathan** for the foundation this fork
builds on — the Deskflow/Barrier wire-protocol client, the AccessibilityService + custom IME
integration, configurable pointer input, and the SHA-256 fingerprint TLS/TOFU model.

Several improvements also build on pull requests contributed to the (then-unmerged) upstream
repository — many thanks to those authors:
- [@qvr — PR #12](https://github.com/jglanz/deskflow-android/pull/12): IME overhaul,
  click-and-drag touch synthesis, and Ctrl+wheel zoom.
- [@RyanEwen — PR #22](https://github.com/jglanz/deskflow-android/pull/22): latency pack
  (TCP_NODELAY), forward mouse button → Recents, wake-on-input, and cursor hide/show on
  screen enter/leave.
- [@chid — PR #21](https://github.com/jglanz/deskflow-android/pull/21): landscape
  connection-screen layout.
- [@Poussinou — PR #6](https://github.com/jglanz/deskflow-android/pull/6): README install
  badges.
Their work was adapted to this fork's architecture (minSdk stays 34; our drag/pointer model
and TLS stack are preserved); see the integration commits in the git history.

The upstream repository became **inactive**, so this is now an **actively maintained fork**
that continues development and has moved well past upstream:
[github.com/kazimurtaza/deskflow-android](https://github.com/kazimurtaza/deskflow-android).
Jonathan's MIT copyright notice is preserved in full — see [LICENSE.md](LICENSE.md).

**Highlights added in this fork:** TLS **on by default**; **Space** toggles the active media
session's play/pause (YouTube, Spotify, …); corrected UTF-8 protocol string framing and
parser-reset-on-reconnect; hardened input reliability (no more mouse-input wedge, drag
chording, wheel re-flush); security hardening (sensitive-clipboard protection, visible TOFU
server fingerprint, SHA-pinned & R8-minified release builds); lifecycle/leak fixes; and a
greatly expanded test suite. See the commit history for the full set.

## Table of Contents

<!-- TOC -->
* [Deskflow Android](#deskflow-android)
  * [Table of Contents](#table-of-contents)
  * [Features & Limitations](#features--limitations)
  * [Installation](#installation)
    * [F-Droid](#f-droid)
    * [Google Play](#google-play)
    * [Github](#github)
    * [Build manually](#build-manually)
  * [Config](#config-)
    * [Deskflow Server](#deskflow-server)
      * [TLS/SSL Security](#tlsssl-security)
    * [Deskflow Android (this app)](#deskflow-android-this-app)
      * [Setup Connection](#setup-connection)
      * [Keyboard (IME)](#keyboard-ime-)
      * [Start/Stop Connection](#startstop-connection)
<!-- TOC -->

## Features & Limitations

- Mouse input
  - Pointer movement, left / right / middle click
  - **Wheel scrolling** (vertical, plus horizontal tilt)
  - **Click-and-drag** (multi-finger for right / middle) and **Ctrl + wheel = pinch/spread zoom**
  - **Back button** triggers system Back; **Forward button** (X2) opens Recents
  - **Wakes the screen** on mouse activity so the tablet responds even while asleep
- Keyboard
  - Shortcuts & hotkeys: app switching (`Command+Tab` / `Alt+Tab`), arrow-key navigation, `Escape` for Back
  - **Volume keys** (up / down / mute) control the device volume
  - **Space** toggles play/pause of the active media session (YouTube, Spotify, …) when no text field is focused
- **Configurable pointer speed** slider (in Settings)
- Clipboard integration
  - Text (of any kind) is supported
  - Clips marked **sensitive** (e.g. password-manager / autofill copies) are **not** relayed to the server
  - __Bitmap is **NOT** supported currently, but is next on the list__
- Simple gesture support
  - Pull down on the status bar
  - Pull up for all apps, etc
- **Mouse-wheel scrolling and click-and-drag both work; arrow keys also navigate in many apps.**
- **TLS is on by default**, with SHA-256 fingerprint trust-on-first-use and client certificates (PeerAuth / mutual TLS) supported. See [TLS/SSL Security](#tlsssl-security).
- **Low-latency input** — TCP_NODELAY is set on the link to the Deskflow server, so mouse/keyboard events aren't delayed by Nagle's algorithm.

## Installation

### F-Droid

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/org.tfv.deskflow/)

### Google Play

Coming soon

### Github

Download the latest APK from the [Releases Section](https://github.com/kazimurtaza/deskflow-android/releases/latest).

### Build manually

1. Clone the repository:  
   ```bash
   git clone git@github.com:kazimurtaza/deskflow-android.git
   ```
2. Open the project in Android Studio.
3. Build and run the app on your Android device or emulator.

## Config 

### Deskflow Server

The only potential configuration change required by the Deskflow Android client, 
is the TLS configuration, described below.

#### TLS/SSL Security

TLS is supported. The client authenticates the server with **SHA-256 fingerprint
trust-on-first-use (TOFU)**: on the first successful connection the server
certificate's fingerprint is pinned, and any later mismatch is rejected (which
would indicate a man-in-the-middle or a changed server certificate). The pinned
server fingerprint is shown on the Settings screen so you can verify it against
the server.

For servers configured with **PeerAuth** (mutual TLS), the client also presents
its own self-signed certificate. The app generates this certificate
automatically on the first TLS connection. Its fingerprint is shown on the
Settings screen so you can register it on the server: append
`v2:sha256:<fingerprint>` to the server's `tls/trusted-clients` file and restart
`deskflow-core` so the new entry is loaded.

> The screenshot below shows the server-side TLS configuration in the Deskflow desktop app.

![Config Screenshot](docs/assets/screenshots/screenshot_tls_config.png)

### Deskflow Android (this app)

On first launch, you'll see a gear in the top right corner of the app,
press the gear (highlighted in the screenshot below) to open the configuration screen.

![Config Screenshot](docs/assets/screenshots/screenshot_tablet_home.png)

#### Setup Connection

The only configuration information required is:

![Config Screenshot](docs/assets/screenshots/screenshot_tablet_config.png)


- `Screen Name` the name you configured in the Deskflow Server.
- `Host` & `Port` of your Deskflow Server.
- `Use TLS` (on by default) — toggle it off only if your Deskflow Server runs without TLS.
- `Pointer speed` adjusts how far the on-screen pointer moves relative to the mouse (1.0x tracks the server exactly; lower is slower and more precise, higher is faster).

Press `Save` to save your configuration and return to the home screen.

> NOTE: As soon as you press `Save`, the app will attempt to connect to the Deskflow Server.

#### Keyboard (IME) 

> IMPORTANT: The Deskflow Android IME, **When Connected to a Deskflow Server**, will always attempt to force the `Deskflow Android Keyboard` ensuring seamless work between Deskflow Server & Client.
> In the case you don't want to use the Deskflow Android Keyboard, you can stop the connection to the Deskflow Server using the button in the top right corner of the app.

The Deskflow Android IME (Input Method Editor) is a custom keyboard that will show the current connection status and will look 
like this screen shot when connected:

![IME Screenshot](docs/assets/screenshots/screenshot_keyboard_ime_active.png)

#### Start/Stop Connection

There is a button in the top right corner of the app to start/stop the connection to the Deskflow Server.

This is useful if you want to either use the regular soft/hard keyboard functionality or simply want to disconnect from the Deskflow Server.
