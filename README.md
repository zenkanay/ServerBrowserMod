# ServerBrowser

A server-side web browser mod for Minecraft (Fabric 1.21.11).

ServerBrowser runs a headless Chromium instance on the server and streams web page renders directly to in-game item frame displays using vanilla map packets. Clients do not need to install any mod to view or interact with the displays.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen.svg)](https://minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-blue.svg)](https://fabricmc.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## Features

- **Server-Side Only**: Vanilla Minecraft clients can connect and see full web displays without installing client-side mods.
- **Headless Chromium Engine**: Utilizes Chrome DevTools Protocol (CDP) to render modern web pages, CSS, JavaScript, and video players.
- **Efficient Streaming**: Streams screencast frames asynchronously, updating only changed map tiles with bilinear scaling and palette quantization.
- **Interactive Mouse Controls**: Raycasting-based cursor interaction with click, double click, drag, and scroll support.
- **Configurable Displays**: Adjust per-display resolution scale, FPS, and JPEG compression quality on the fly, within server operator limits.
- **Display Protection**: Remote wands are bound 1:1 to displays; item frames are protected from unauthorized rotation or destruction.

---

## Requirements

- **Minecraft**: 1.21.11 (Fabric)
- **Java**: 21 or higher
- **Server Host**: Google Chrome, Chromium, or Microsoft Edge installed on the host operating system.

---

## Installation

1. Place `serverbrowser-<version>.jar` and `fabric-api` into the server `mods/` directory.
2. Ensure Chrome or Edge is installed on the host machine (ServerBrowser automatically detects standard browser paths).
3. Start the server. Configuration files are generated in `config/serverbrowser.json`.

---

## Controls

Interactions require holding the dedicated **Remote Wand** bound to the target display:

| Action | Control |
| :--- | :--- |
| **Click / Focus** | Right Click |
| **Double Click** | Double Right Click |
| **Drag (Toggle)** | Shift + Right Click |
| **Scroll Up** | Left Click (swing in air) |
| **Scroll Down** | Shift + Left Click (swing in air) |

---

## Commands

### Player Commands

| Command | Description |
| :--- | :--- |
| `/browser create [width] [height]` | Spawn a display and receive its Remote Wand |
| `/browser destroy` | Destroy the display and wand held in hand |
| `/browser open <url or query>` | Navigate to a URL or search query |
| `/browser google <query>` | Shortcut for Google Search |
| `/browser reload` | Reload the current page |
| `/browser back` / `/browser forward` | Navigate history back / forward |
| `/browser type <text>` | Type text into the active input element |
| `/browser backspace [count]` | Send Backspace (alias: `/browser bs`) |
| `/browser enter` | Send Enter key |
| `/browser drag` | Toggle mouse drag mode |
| `/browser click` / `rclick` / `dclick` | Trigger mouse click via command |
| `/browser scroll [up\|down\|amount]` | Scroll page via command |
| `/browser wand` | Reacquire wand for nearest display within 10 blocks |
| `/browser list` | List active displays and current properties |
| `/browser fps <fps>` | Set frame rate for held display (1 to server max) |
| `/browser quality <quality>` | Set JPEG quality for held display (10 to 100, alias: `/browser qual`) |
| `/browser res <scale>` | Set resolution scale for held display (alias: `/browser resolution`) |

### Operator Commands (OP Required)

| Command | Description |
| :--- | :--- |
| `/browser destroy_near` | Remove nearest display without holding wand |
| `/browser destroy_id <id>` | Remove specific display by ID |
| `/browser wand_id <id>` | Obtain Remote Wand for specific display by ID |
| `/browser fps_id <id> <fps>` | Set frame rate for display by ID |
| `/browser quality_id <id> <quality>` | Set JPEG quality for display by ID |
| `/browser res_id <id> <scale>` | Set resolution scale for display by ID |
| `/browser max_fps <fps>` | Set global server maximum FPS limit (1 to 60) |
| `/browser max_quality <quality>` | Set global server maximum JPEG quality limit (10 to 100) |
| `/browser max_res <scale>` | Set global server maximum resolution scale limit (0.5 to 4.0) |
| `/browser reset` | Delete all displays and reset screen ID counter |

---

## Configuration

Configuration is located at `config/serverbrowser.json`:

```json
{
  "customChromePath": "",
  "defaultUrl": "https://www.google.com",
  "maxDisplayWidth": 100,
  "maxDisplayHeight": 100,
  "maxTotalScreens": 100,
  "requireOpToCreate": false,
  "liveRefreshIntervalMs": 100,
  "maxInteractionDistance": 96.0,
  "enableStealthMode": true,
  "blockedDomains": [],
  "maxFps": 30,
  "defaultFps": 14,
  "maxQuality": 100,
  "defaultQuality": 75,
  "maxScale": 2.0,
  "defaultScale": 1.0
}
```

---

## Branch Strategy

This repository organizes development branches using `<mc-version>-<loader>` naming conventions:

- **`main`**: Primary active branch (tracking the latest stable release line, Fabric 1.21.x).
- **`1.21-fabric`**: Dedicated branch for Minecraft 1.21.x on Fabric.
- **`1.21-neoforge`**: (Future) Port for Minecraft 1.21.x on NeoForge.
- **`1.20.x-<loader>`**: (Future) Backports for earlier Minecraft versions.

---

## License

This project is licensed under the [MIT License](LICENSE).
