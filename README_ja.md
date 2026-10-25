# ServerBrowser (サーバーブラウザ)

Minecraft (Fabric 1.21.11) 向けの完全サーバーサイド対応 Webブラウザ MOD。

サーバー側でバックグラウンド起動した Chromium (Chrome / Edge / Brave 等) のレンダリング画面を、バニラの地図パケットに変換して額縁ディスプレイにリアルタイムストリーミングします。
**クライアント側には MOD の導入が一切不要（完全バニラ環境で閲覧・操作可能）**です。

[English README is available here](README.md)

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen.svg)](https://minecraft.net/)
[![Fabric](https://img.shields.io/badge/Loader-Fabric-blue.svg)](https://fabricmc.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## 主な特徴

- **完全サーバーサイド動作**: クライアントはバニラのままでOK。誰でもそのまま高精細なWeb画面を閲覧・操作できます。
- **本物の Chromium (CDP) エンジン**: Chrome DevTools Protocol を利用し、YouTube や現代的な Webサイト (HTML5, CSS, JavaScript, WebGL) を滑らかに描画・再生（最大 4K 対応）。
- **6方向マルチタイル設置**: 壁面だけでなく、床面（上向き）や天井（下向き）にも設置可能。天井設置時の上下左右反転バグも完全解消。
- **新しいタブ・ポップアップの同一画面遷移**: `<a target="_blank">` や `window.open()` などの「新しいタブで開く」リンクも、現在のディスプレイ画面内でシームレスに遷移（リンクが反応しない問題を根絶）。
- **Webサイト表示とGoogle検索の完全統合**: `/browser open` に URL を入力すれば直接サイトが開き、日本語や検索ワードを入力すれば自動的に Google 検索結果が開きます。
- **カーソル倍率の個別設定 ＆ 解像度（res）自動連動**: ディスプレイごとにカーソルサイズを自由に変更可能（`/browser cursor <倍率>`）。解像度（`res`）を変更した際もカーソルサイズが自然に自動連動します。
- **超高速 GPU Screencast ストリーミング**: 変化のあった地図タイルのみをダイレクトバッファサンプリングで検出し、無駄なパケットを 80% 以上削減。
- **専用リモコン棒による直感的なマウス操作**: 視線 Raycast による 1:1 のマウスポインタ追従、クリック、素早いダブルクリック、ドラッグ＆ドロップ、適応スクロールに対応。
- **ディスプレイ保護機能**: リモコン棒はディスプレイごとに 1:1 でバインドされ、他プレイヤーによる額縁の回転や破壊、背面の壁ブロック破壊を完全ブロック。

---

## 動作環境

- **Minecraft**: 1.21.11 (Fabric)
- **Java**: 21 以上
- **サーバーホスト**: Google Chrome、Chromium、または Microsoft Edge がインストールされていること（標準パスを自動検出します）。

---

## 導入方法

1. サーバーの `mods/` フォルダに `serverbrowser-<version>.jar` および `fabric-api` を配置します。
2. サーバーマシンに Chrome や Edge がインストールされていることを確認します。
3. サーバーを起動します。初回起動時に `config/serverbrowser.json` が自動生成されます。

---

## 操作方法 (リモコン棒)

対応するディスプレイの **「ブラウザリモコン」** をメインハンド（右手）に持っている時のみ操作できます：

| 操作 | 動作 |
| :--- | :--- |
| **右クリック** | クリック / 入力欄フォーカス |
| **素早く2回右クリック** | ダブルクリック (全画面化や再生/一時停止など) |
| **Shift + 右クリック** | ドラッグ＆ドロップ (つかむ / 離す) |
| **左クリック (空振り可)** | 上へスクロール ▲ |
| **Shift + 左クリック (空振り可)** | 下へスクロール ▼ |

---

## コマンド一覧

### 一般プレイヤー用コマンド

| コマンド | 説明 |
| :--- | :--- |
| `/browser help` | 操作ガイドを表示 (`/browser help op` で管理者ヘルプ) |
| `/browser create [横] [縦]` | 指定サイズ（タイル数）のディスプレイを設置し、専用リモコン棒を取得 |
| `/browser destroy` | 手に持っているリモコン棒に対応するディスプレイと棒を撤去 |
| `/browser open <URLまたは検索ワード>` | Webサイトを開く、または Google 検索を実行 (自動判定) |
| `/browser home` | デフォルトのホームページを開く |
| `/browser reload` | 現在のページを再読み込み |
| `/browser back` / `/browser forward` | 履歴を戻る / 進む |
| `/browser type <テキスト>` | 選択中の入力欄にテキストを入力 |
| `/browser backspace [回数]` | Backspace キーを送信 (文字を削除) |
| `/browser enter` | Enter キーを送信 (フォーム送信や検索決定) |
| `/browser click` / `rclick` / `dclick` | コマンドで左クリック / 右クリック / ダブルクリックを実行 |
| `/browser drag` | ドラッグモードを切り替え (つかむ / 離す) |
| `/browser scroll [up\|down\|数値]` | コマンドで画面を上下スクロール |
| `/browser wand` | 最寄りのディスプレイ（10ブロック以内）のリモコン棒を再取得 |
| `/browser fps <FPS>` | 手持ちディスプレイの描画FPSを変更 (1〜サーバー上限) |
| `/browser res <倍率>` | 手持ちディスプレイの解像度倍率を変更 (例: 0.5, 1.0, 2.0) |
| `/browser cursor <倍率>` | 手持ちディスプレイのカーソル倍率を変更 (0.1〜10.0, resと自動連動) |
| `/browser quality <品質>` | 手持ちディスプレイのJPEG品質を変更 (10〜100) |
| `/browser list` | 設置中のディスプレイ一覧と設定プロパティを表示 |
| `/browser info` | 手持ちディスプレイの詳細プロパティを表示 |

### 管理者（OP）用コマンド

| コマンド | 説明 |
| :--- | :--- |
| `/browser help op` | 管理者用コマンド一覧を表示 |
| `/browser destroy_near` | 棒不要で一番近くのディスプレイを強制撤去 |
| `/browser destroy_id <ID>` | 指定したIDのディスプレイを強制撤去 |
| `/browser reset` | 全ディスプレイを一括消去し、番号を #1 に完全リセット |
| `/browser wand_id <ID>` | 指定したIDのリモコン棒を取得 |
| `/browser info_id <ID>` | 指定したIDのディスプレイの詳細プロパティを表示 |
| `/browser fps_id <ID> <FPS>` | 指定画面の描画FPSを変更 |
| `/browser res_id <ID> <倍率>` | 指定画面の解像度倍率を変更 |
| `/browser cursor_id <ID> <倍率>` | 指定画面のカーソル倍率を変更 (0.1〜10.0) |
| `/browser quality_id <ID> <品質>` | 指定画面のJPEG品質を変更 |
| `/browser max_fps <FPS>` | サーバー全体の最大FPS上限を設定 (1〜60) |
| `/browser max_res <倍率>` | サーバー全体の最大解像度スケール上限を設定 (0.01〜) |
| `/browser max_quality <品質>` | サーバー全体の最大JPEG品質上限を設定 (10〜100) |

---

## 設定ファイル (`config/serverbrowser.json`)

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

## ライセンス

本プロジェクトは [MIT License](LICENSE) のもとで公開されています。
