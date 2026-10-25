# ServerBrowser

A server-side web browser mod for Minecraft (Fabric 1.21.11).

ServerBrowser runs a headless Chromium instance on the server and streams web page renders directly to in-game item frame displays using vanilla map packets. Clients do not need to install any mod to view or interact with the displays.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen.svg)](https://minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-blue.svg)](https://fabricmc.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## Features

- **Server-Side Only**: Vanilla Minecraft clients can connect and see full web displays without installing client-side mods.
- **Headless Chromium Engine**: Utilizes Chrome DevTools Protocol (CDP) to render modern web pages, CSS, JavaScript, and HTML5 video players (up to 4K).
- **6-Directional Mounting**: Mount displays on walls, floors, or ceilings (downward-facing ceiling displays are properly oriented).
- **Seamless Navigation & Tab Interception**: Automatically redirects `<a target="_blank">`, `window.open()`, and external links to navigate inside the current display without getting stuck.
- **Unified Open & Google Search**: `/browser open` intelligently detects whether the input is a URL or a search query and navigates or searches on Google accordingly.
- **Adaptive Cursor & Resolution Scaling**: Change per-display resolution (`/browser res`) with cursors automatically resizing to match. Fine-tune cursor size individually with `/browser cursor <scale>` with seamless multi-tile rendering.
- **Efficient Streaming**: Streams GPU screencast frames asynchronously, updating only changed map tiles with direct buffer sampling and palette quantization.
- **Interactive Mouse & Wand Controls**: Raycasting-based cursor interaction with click, double click, drag, and adaptive scrolling via dedicated Remote Wands.
- **Display Protection**: Remote wands are bound 1:1 to displays; item frames and backing wall blocks are protected from unauthorized rotation or destruction.

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
| `/browser help` | Show concise user guide (`/browser help op` for admin commands) |
| `/browser create [width] [height]` | Spawn a display and receive its Remote Wand |
| `/browser destroy` | Destroy the display and wand held in hand |
| `/browser open <url or query>` | Navigate to a URL or search on Google (auto-detected) |
| `/browser home` | Navigate to default home page |
| `/browser reload` | Reload the current page |
| `/browser back` / `/browser forward` | Navigate history back / forward |
| `/browser type <text>` | Type text into the active input element |
| `/browser backspace [count]` | Send Backspace |
| `/browser enter` | Send Enter key |
| `/browser click` / `rclick` / `dclick` | Trigger left, right, or double click via command |
| `/browser drag` | Toggle mouse drag mode (grab / release) |
| `/browser scroll [up\|down\|amount]` | Scroll page via command |
| `/browser wand` | Reacquire wand for nearest display within 10 blocks |
| `/browser fps <fps>` | Set frame rate for held display (1 to server max) |
| `/browser res <scale>` | Set resolution scale for held display (e.g. 0.5, 1.0, 2.0) |
| `/browser cursor <scale>` | Set cursor scale for held display (0.01 to 100.0, links with res) |
| `/browser quality <quality>` | Set JPEG quality for held display (1 to 100) |
| `/browser list` | List active displays and current properties |
| `/browser info` | View detailed properties of held display |

### Operator Commands (OP Required)

| Command | Description |
| :--- | :--- |
| `/browser help op` | Show admin commands help |
| `/browser destroy_near` | Remove nearest display without holding wand |
| `/browser destroy_id <id>` | Remove specific display by ID |
| `/browser reset` | Delete all displays and reset screen ID counter |
| `/browser wand_id <id>` | Obtain Remote Wand for specific display by ID |
| `/browser info_id <id>` | View detailed properties of display by ID |
| `/browser fps_id <id> <fps>` | Set frame rate for display by ID |
| `/browser res_id <id> <scale>` | Set resolution scale for display by ID |
| `/browser cursor_id <id> <scale>` | Set cursor scale for display by ID (0.01 to 100.0) |
| `/browser quality_id <id> <quality>` | Set JPEG quality for display by ID |
| `/browser max_fps <fps>` | Set global server maximum FPS limit (1 to 120) |
| `/browser max_res <scale>` | Set global server maximum resolution scale limit (0.001+) |
| `/browser max_quality <quality>` | Set global server maximum JPEG quality limit (1 to 100) |

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
  "maxScale": 10.0,
  "defaultScale": 1.0
}
```

---

## License

This project is licensed under the [MIT License](LICENSE).
