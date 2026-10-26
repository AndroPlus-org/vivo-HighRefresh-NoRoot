# vivo-HighRefresh-NoRoot

[English](README.md)

vivo スマホ（OriginOS / Funtouch OS）のリフレッシュレートを、root なしで 144Hz（対応パネルなら 165Hz）に固定します。アプリか adb のどちらでも使え、root・Magisk・ブートローダーアンロックは不要です。

## アプリ（vivo HiHz）

### [最新の APK をダウンロード](https://github.com/AndroPlus-org/vivo-HighRefresh-NoRoot/releases/latest)

いちばん簡単な方法です。adb も Shizuku も特別な権限も要りません。

1. APK をインストールして **vivo HiHz** を開きます。
2. 固定したいレートをタップします。パネルが対応しているレートだけが表示されます。
3. 元に戻すときは **解除（自動に戻す）** をタップします。

**再起動後も自動で適用する** をオン（初期設定）にしておくと、端末を再起動したあとも同じレートを自動で固定し直します。画面には現在のレート、固定中のレート、採用中の要求が表示されます。表示は日本語と英語に対応しています。

### アプリごとのレート

120fps が上限のゲームや動画アプリは、165Hz に固定すると半分の 82.5fps になります。また固定は vivo 純正のアプリ別リフレッシュレート設定より優先されます。**アプリごとのレート** を使うと、固定をオンにしたまま、アプリごとに別のレート（例: 120Hz）を指定したり、**自動（固定しない）** にしてそのアプリが前面にある間だけ固定を外し、アプリと vivo のアプリ別設定に任せたりできます。**固定レートに従う** のままのアプリは固定レートで動きます。

切り替えには前面のアプリを知る方法が必要です。**前面アプリの検出方法** で次から選びます。

- **ユーザー補助**（おすすめ）: このアプリのユーザー補助サービス（**vivo HiHz アプリごとのレート**）を使います。前面のアプリが何かを知るためだけに使い、画面の内容は読み取りません。システムが動かし続けます。Android 13 以降で APK から入れた場合はグレーアウトしていることがあります。そのときはアプリ情報を開き、︙ から **制限付き設定を許可** を選んでからオンにします。
- **使用状況へのアクセス**: 使用状況へのアクセスで vivo HiHz を許可します。画面がオンの間、1秒ごとに前面のアプリを確認するので、切り替えが最大1秒ほど遅れます。
- **Shizuku**: [Shizuku](https://shizuku.rikka.app/) を起動し、vivo HiHz を許可します。アクティビティマネージャーにプロセスの監視を登録するので、切り替えはすぐ届きます。登録後は Shizuku が止まっても動き続けますが、再起動後は Shizuku を起動し直す必要があります。

使用状況へのアクセスと Shizuku は、vivo に終了されないよう通知付きのフォアグラウンドサービスで動きます。

リリースはすべて GitHub Actions でビルドし同じ鍵で署名しているので、新しいバージョンをそのまま上書きインストールできます。

## コマンドライン

### 必要なもの

- adb（USB またはワイヤレスデバッグ）
- bash（Linux、macOS、WSL、Git Bash、Termux のいずれか）、または Windows の PowerShell
- dex を自分でビルドし直す場合のみ：JDK 17 と Android SDK build-tools の `d8`（ビルド済みの `RmsVote.dex` を同梱しています）

### 使い方

```bash
./vivo-rms.sh modes          # パネルが対応するレートとベンダーの制限
./vivo-rms.sh set 144        # 144Hz に固定
./vivo-rms.sh get            # 現在のレート（例: rate=144.0）
./vivo-rms.sh reason         # 採用中の要求（例: RmsVote|144）
./vivo-rms.sh release 0 900001   # 元に戻す
```

Windows では `vivo-rms.cmd` を同じコマンドで使います（実行ポリシーを変えずに `vivo-rms.ps1` を実行します）。

```bat
.\vivo-rms.cmd modes
.\vivo-rms.cmd set 144
.\vivo-rms.cmd release 0 900001
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

一般のアプリからも、リフレッシュレートの要求先（`rms` サービス）を取得できます（`ServiceManager.getService` をリフレクションで呼びます）。サーバーは UID を確認せず、`caller` が 0 なら system_server の要求として扱うので、アプリは権限なしで同じ Parcel を送れます。要求はメモリ上にしか残らないため、アプリは `BOOT_COMPLETED` を受け取ったときに送り直します。

## 対応機種

検証したのは vivo 端末2台です（iQOO 15 Ultra 144Hz、iQOO Pad Ultra 165Hz）。

他の機種でも、`./vivo-rms.sh modes` に目的のレートが表示され、`adb shell service list` に `rms: [com.vivo.rms.IRM]` があれば動く見込みです。動かない場合は `/system/framework/vivo-services.jar` を取り出し、`RMNative.onTransact` を上の表と比べてください。

## 注意

**自己責任で使ってください。** 非公開のベンダーインターフェースで画面を制御するため、電池の消費と発熱が増えます。

## ライセンス

[MIT](LICENSE)
