# vivo-HighRefresh-NoRoot

[English](README.md)

vivo スマホ（OriginOS / Funtouch OS）のリフレッシュレートを、root なしで 144Hz（対応パネルなら 165Hz）に固定します。adb だけで動き、root・Magisk・ブートローダーアンロックは不要です。

## 必要なもの

- adb（USB またはワイヤレスデバッグ）
- bash（Linux、macOS、WSL、Git Bash、Termux のいずれか）、または Windows の PowerShell
- dex を自分でビルドし直す場合のみ：JDK 17 と Android SDK build-tools の `d8`（ビルド済みの `RmsVote.dex` を同梱しています）

## 使い方

```bash
./vivo-rms.sh modes          # パネルが対応するレートとベンダーの制限
./vivo-rms.sh set 144        # 144Hz に固定
./vivo-rms.sh get            # 現在のレート（例: rate=144.0）
./vivo-rms.sh reason         # 採用中の要求（例: RmsVote|144）
./vivo-rms.sh release 0 900001   # 元に戻す
```

Windows では `vivo-rms.cmd` を同じコマンドで使います（実行ポリシーを変えずに `vivo-rms.ps1` を実行します）。

```bat
vivo-rms.cmd set 144
vivo-rms.cmd release 0 900001
```

固定は解除するか再起動するまで続きます。`hold <fps>` を使うと、Ctrl-C を押したときに自動で解除されます。

アプリが自分で表示モードを指定している場合は、そのアプリが前面にある間はアプリ側の指定が優先されます。このとき `reason` は `reqFps:<アクティビティ>|<fps>` を表示します。

## 仕組み

vivo のフレームワーク（`com.vivo.rms.common.RefreshRateRequester`）は、`rms` サービス（`com.vivo.rms.IRM`、system_server 内の `vivo-services.jar` が提供）にリフレッシュレートを要求しています。このとき**インターフェーストークンを付けない** Parcel を送っています。

`service call` はトークンを必ず先頭に付けるので、引数の位置がずれて受け付けられません。そこで `RmsVote.dex` を `app_process` で動かし、フレームワークと同じ形式で送っています。

| tx | メソッド | 引数 |
|---|---|---|
| 23 | acquireRefreshRate | long handle, String scene, String reason, int fps, priority, duration, caller, client, states, dfps, extra |
| 24 | releaseRefreshRate | int caller, long handle |
| 26 | getActiveRefreshRate | なし（float を返す） |
| 31 | acquireRefreshRate (V2) | long handle, String scene, String reason, int display, resolution, fps, dfps, configBits, states, priority, duration, caller, client, flags, arg1, arg2 |

サーバー側（`com.vivo.services.rrm.RefreshRateAdjuster`）の受付条件は次のとおりです。

- リフレッシュレート関連の呼び出しには **UID チェックがありません**。
- `caller` は AMS が管理しているプロセスである必要があります。shell から起動した `app_process` はこれに該当しないため、要求は何も返さずに捨てられます。
- **`caller` に 0 以下を渡すと、system_server 自身の要求として扱われます**。ベンダー純正の `dumpsys rms --display --set-fps` も同じ方法を使っています。ただしこのコマンドは `persist.sys.rms.allow_dump` が必要で、shell からは設定できません。
- 144Hz は「プライベート fps」（`persist.sys.private_fps`）扱いで、フラグ `2`（プライベート fps を使う）を付けたときだけ選ばれます。
- 既定値はベンダーの adb 用経路に合わせています（シーン `FixRate`、優先度 100、フラグ `15`、ディスプレイ `-1`）。
- system_server は同じ caller の下で自分の要求に handle 1 から番号を振るので、重ならないよう既定の handle を 900001 にしています。

## 対応機種

検証したのは vivo 端末1台です（iQOO 15 Ultra、1440×3168 パネル、60/90/120/144Hz、`RefreshRateAdjuster` SDK 230409）。

他の機種でも、`./vivo-rms.sh modes` に目的のレートが表示され、`adb shell service list` に `rms: [com.vivo.rms.IRM]` があれば動く見込みです。動かない場合は `/system/framework/vivo-services.jar` を取り出し、`RMNative.onTransact` を上の表と比べてください。

## 注意

**自己責任で使ってください。** 非公開のベンダーインターフェースで画面を制御するため、電池の消費と発熱が増えます。

## ライセンス

[MIT](LICENSE)
