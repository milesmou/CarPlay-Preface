# CarPlay-Preface

[中文说明](README.zh-CN.md) · [Upstream DiPlay](https://github.com/shihabal3amri/DiPlay)

This repository is based on DiPlay and primarily targets **the Geely Preface (Xingrui) with the E02 head-unit chip**.

App version: **0.1.0**. Upstream baseline: [DiPlay v0.2.18](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.18). When syncing upstream, update the upstream baseline and increment this repository's app version.

[0.1.0 preview](https://github.com/milesmou/CarPlay-Preface/releases/tag/v0.1.0) · [Release notes](docs/RELEASE-NOTES-0.1.0.md). The signed source-preview APK requires separately provisioned CarPlay authentication assets for standalone iPhone connections. Vehicle validation and the full CI suite remain incomplete.

## Differences from upstream

- **Adaptation target:** This branch primarily targets the Xingrui E02 head unit.
- **Firmware 327 integration:** Stock media-button broadcasts on IHU519G/FS11A1 control previous/next tracks and play/pause. Broadcasts, MediaSession and foreground keys share deduplication; background track control works while CarPlay owns media focus. Native media stream 3/navigation stream 12 are routing defaults, preserving saved routes and stock volume-key handling. See [firmware analysis](docs/PREFACE_FIRMWARE_327.md); vehicle validation remains pending.
- **Direct settings navigation:** Overview is removed. Settings opens on Connection, with a rail on wide windows and horizontally scrolling destinations on compact windows.
- **Wheel keys and independent volume:** Standard play/pause and track keys are handled in the CarPlay window, with Bluetooth audio avoiding duplicate forwarding. Media and guidance have independent output gain controls. During guidance, standard volume keys can adjust guidance alone; generic background key handling needs the accessibility service. Other E02 firmware interfaces still need separate confirmation; vehicle validation remains pending.
- **Experimental menu and branding:** Experimental controls are grouped under Experimental. The app is named CarPlay and the default vehicle button uses a generated Xingrui icon and the name 星瑞.
- **Chinese-only interface:** Settings and the in-CarPlay language picker are removed; the app always uses Simplified Chinese.
- **Four-step setup:** Connection method → iPhone → automatic connection and location sharing → finish. The vehicle/DiLink generation selection has been removed.
- **BYD-specific integrations removed:** HUD and cluster navigation, DiLink cluster display/task routing, battery/speed/gear/tyre readers, ambient lighting, proprietary call cards and key codes, Bluetooth suspension, Wi-Fi scan pausing and boot-start repair.
- **Shared functionality retained:** Wired/wireless CarPlay, Android location sharing, media/Siri, learned wheel keys, Geely input-service integration, launcher/centre-card maps and optional FCE navigation output. This does not establish real-vehicle compatibility with the Xingrui E02 version.

## Reference documentation

- [Xingrui E02 firmware reference](docs/PREFACE_FIRMWARE_REFERENCE.md): reusable evidence for wheel keys, audio/microphone, connectivity, navigation, video, permissions and power, with vehicle checks still pending.
- [Upstream features and releases](https://github.com/shihabal3amri/DiPlay)
- [Installation and connection](docs/INSTALL.md)
- [Build from source](docs/BUILD.md) — use the `mobile` module for the main app.
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

When reporting a problem, include the Xingrui model year, E02 head-unit firmware and Android version, iPhone/iOS version, connection method and reproduction steps.
