# ServerBrowser 🌐

> **Pure Server-Side In-Game Web Browser for Minecraft (Fabric 1.21.11)**
> Zero client mods required. Vanilla Minecraft clients can connect and browse the real internet!

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen.svg)](https://minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-blue.svg)](https://fabricmc.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## English

### ✨ Key Features
- **100% Server-Side**: Players do **NOT** need to install any mod! Vanilla clients can join and see the screens.
- **Real Chromium CDP Engine**: Runs headless Chrome/Edge in the background with Chrome DevTools Protocol. Renders real CSS layouts, images, and HTML.
- **Ultra-Smooth 14 FPS Autonomous Streaming**: GPU-accelerated CDP screencasting with worker-thread differential tile rendering. Zero server tick blocking.
- **PC-Like Mouse Hover & Smart Auto-Hide**: Sight raycast triggers native CSS `:hover` states. Stationary sight for 1.5s smoothly auto-hides YouTube player controllers and cursor for an immersive full-screen cinematic experience.
- **Mouse Click & Drag**: Grab and slide YouTube seek bars, volume controls, Google Maps panning, and text selection just like on a real PC!
- **Gigantic Displays (Up to 100x100)**: Create theater screens (32x18, 48x27), stadium megavisions (64x36), or up to 100x100 blocks with dedicated map ID ranges and auto-distance packet scaling.
- **Multiple Independent Screens**: Place as many displays as you want in your world! Each display has its own independent browser tab and isolated incognito session.
- **Dedicated Remote Wands (1:1 Binding)**: Automatic wand given per display. Mouse pointer only appears and interacts when holding the corresponding wand.
- **SMP Safe & Grief Protected**: Remote wand locks item frames from unauthorized punches and rotations. Dangerous commands require server operator (OP) permission.

---

### 🎮 Controls (Remote Wand)
Hold the dedicated **Remote Wand** in hand to interact:
- **Right Click**: Mouse Click (Focus input boxes, click links/buttons)
- **Quick Double Right Click**: Double Click (Native double-click trigger)
- **Shift + Right Click**: **Mouse Drag Toggle** (Grab seek bars, sliders, maps; cursor turns gold, click again to drop!)
- **Left Click**: Scroll Up ▲
- **Shift + Left Click**: Scroll Down ▼
- **/browser drag**: Toggle mouse drag mode via command

---

### 💻 Commands

#### Standard Player Commands (No OP required)
| Command | Description |
| :--- | :--- |
| `/browser create [width] [height]` | Spawn display (up to 100x100) and receive dedicated Remote Wand |
| `/browser destroy` | Destroy the display and wand currently held in hand |
| `/browser open <url or query>` | Open website or search query |
| `/browser google <query>` | Shortcut for Google Search |
| `/browser reload` | Reload the current web page |
| `/browser back` / `forward` | Navigate back / forward in browser history |
| `/browser type <text>` | Type text into active input field |
| `/browser backspace [count]` | Send Backspace to delete characters (alias: `/browser bs`) |
| `/browser enter` | Press Enter key to submit search or form |
| `/browser drag` | Toggle mouse click & drag mode |
| `/browser click` / `rclick` / `dclick` | Mouse Click / Right Click / Double Click at cursor |
| `/browser scroll [up\|down\|amount]` | Scroll page via command (e.g. `/browser scroll up 200`) |
| `/browser wand` | Reacquire Remote Wand for the closest display within 10 blocks |
| `/browser list` | View list of all active displays |

#### Admin / Operator Commands (OP Required)
| Command | Description |
| :--- | :--- |
| `/browser destroy_near` | Force remove nearest display without holding wand |
| `/browser destroy_id <id>` | Force remove specific display by ID |
| `/browser wand_id <id>` | Obtain Remote Wand for any specific display by ID |
| `/browser reset` | Delete all displays and reset screen ID counter to #1 |

---

### ⚙️ Server Configuration (`config/serverbrowser.json`)
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
  "blockedDomains": []
}
```

---

## 日本語 (Japanese)

### ✨ 主な特徴
- **完全サーバーサイド動作**: クライアント側の mod 導入は一切不要！バニラクライアントで参加してそのまま巨大ディスプレイのブラウザを楽しめます。
- **超高速 13〜14 FPS 自律ストリーミング**: Chrome の GPU Screencast を直接受信し、ワーカースレッドでタイル差分処理。サーバー Tick を 0.01ms もブロックしません。
- **PC ライクなマウスホバー＆スマート自動非表示**: 視線移動でリアルな `:hover` スタイルが発動。画面をじっと見つめると 1.5 秒でカーソルや YouTube のコントローラー・暗い影が自動でフェードアウトし、動画本体だけが最高画質でクリア再生されます。
- **マウス クリック＆ドラッグ対応**: `Shift + 右クリック` でつかみ、視線で YouTube のシークバーや音量、Google マップの移動、テキスト選択などを本物の PC 同様にグリグリ移動できます！
- **最大 100×100 の超特大スクリーン対応**: 映画館スクリーン（32×18、48×27）やスタジアムメガビジョン（64×36）など、最大 100×100 ブロックの超巨大スクリーンを作成可能。
- **1:1 連動リモコン棒**: 画面ごとに専用のリモコン棒を付与。棒を持っている時だけ視線ポインターが表示され、遠隔クリック・スクロールができます。
- **SMP（マルチサーバー）安全設計**: 荒らしに悪用されやすい画面の強制撤去やリモコン強奪は管理者（OP）のみに制限。一般プレイヤー同士の画面へのいたずらを防止します。

---

### 🎮 リモコン棒の操作方法
対応する**リモコン棒**を手（メインハンド）に持って操作します：
- **右クリック**: マウスクリック（リンクを開く、入力欄のフォーカス）
- **素早く2回右クリック**: ダブルクリック（フォルダ展開、拡大など）
- **Shift + 右クリック**: **ドラッグ開始 / 終了**（つかむとカーソルが金色に変化！視線で移動し、もう一度クリックでドロップ）
- **左クリック (素振り可)**: 上へスクロール ▲
- **Shift + 左クリック**: 下へスクロール ▼

---

### 💻 コマンド一覧

#### 一般プレイヤー用コマンド（OP不要）
| コマンド | 説明 |
| :--- | :--- |
| `/browser create [横] [縦]` | 最大 100x100 のディスプレイを設置し、専用リモコン棒を入手 |
| `/browser destroy` | 手に持っているリモコン棒に対応する画面と棒を撤去 |
| `/browser open <URLまたは検索語>` | ページを開く / Google検索 |
| `/browser google <検索語>` | Google 検索を実行 |
| `/browser reload` | 開いているページを再読み込み（リロード） |
| `/browser back` / `forward` | 前のページに戻る / 次のページに進む |
| `/browser type <テキスト>` | 選択中の入力欄に文字を入力 |
| `/browser backspace [回数]` | 入力欄の文字を削除（短縮: `/browser bs`） |
| `/browser enter` | 検索実行・フォーム送信の Enter キーを押す |
| `/browser drag` | ドラッグモードのトグル（つかむ / 離す） |
| `/browser click` / `rclick` / `dclick` | 左クリック / 右クリック / ダブルクリック |
| `/browser scroll [up\|down\|数値]` | コマンドでスクロール（例: `/browser scroll down 200`） |
| `/browser wand` | 付近10ブロック以内で最も近い画面のリモコン棒を再取得 |
| `/browser list` | ワールド内の設置中ディスプレイ一覧を表示 |

#### サーバー管理者用コマンド（OP必須）
| コマンド | 説明 |
| :--- | :--- |
| `/browser destroy_near` | 棒が手元にない場合でも最寄りの画面を強制撤去 |
| `/browser destroy_id <ID>` | 指定したIDの画面を強制撤去 |
| `/browser wand_id <ID>` | 指定したIDのリモコン棒を強制取得 |
| `/browser reset` | 全画面を一括消去し、画面IDカウンターを #1 に完全リセット |

---

### 📄 License
MIT License. Free to use in modpacks, public servers, and redistributions.
