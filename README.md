# DUNavi

Android端末をBluetooth DUN（Dial-up Networking）モデムとして待ち受け、対応カーナビのIPv4通信をスマートフォン回線へ転送するアプリです。root、`VpnService`、隠しAPI、システムテザリングは使用しません。

主対象はBluetooth DUN対応のT-Connectナビです。一般的なDUNクライアントとの互換も意識していますが、Androidには公開DUNプロファイルAPIがないため、公開RFCOMM API上にDUN/AT/PPPと最小のIPv4 TCP/UDP転送を実装しています。

[GitHub Releases](https://github.com/ogatomo21/DUNavi/releases) で署名付きAPKを配布する構成です。自分でビルド・公開する場合は [リリース手順](docs/releasing.md) を参照してください。

## 要件

- Android 9（API 28）以上
- Bluetooth対応端末
- モバイルデータまたはWi-Fiによるインターネット接続
- ナビとAndroid端末のBluetoothペアリング
- T-Connect側で必要な契約・利用開始手続き

## 使い方

1. AndroidのBluetooth設定でナビ（通常は `CAR MULTIMEDIA`）とペアリングします。
2. DUNaviを開き、Bluetooth権限と通知権限を許可します。
3. 必要なら「検出可能にする」を押し、「待受を開始」を押します。
4. ナビ側で「オンライン」→「通信設定」→「Bluetooth」を選び、契約中の通信事業者を選択してセンター接続を実行します。
5. 常用する場合は「端末起動時に自動待機」を有効にします。起動後は継続通知から停止できます。

トヨタの公式資料も確認してください。

- [T-Connect 携帯接続について](https://toyota.jp/tconnectservice/intro/connection.html)
- [携帯接続ご利用ガイド（PDF）](https://toyota.jp/pages/contents/tconnectservice/contents/pdf/userguide_mobileconnect.pdf)
- [T-Connect携帯接続の終了時期（PDF）](https://toyota.jp/pages/contents/tconnectservice/contents/pdf/mobile_connection_plan.pdf)

公式案内では対象ナビ、申込時期、契約状態により利用可否と終了時期が異なります。車両・ナビ型番・契約状態を必ず確認してください。

## 実装範囲

- Bluetooth DUN UUID `00001103-0000-1000-8000-00805F9B34FB` のsecure RFCOMM待受
- DUN/V.250の基本ATコマンド、`ATD*99#` / `ATDT*99***n#` / `AT+CGDATA="PPP",n`、legacy DUNの通常番号ATD
- PPP async HDLC/FCS、双方向LCP/IPCP、legacy PAP応答、DNS negotiation
- 端末側 `192.168.44.1`、ナビ側 `192.168.44.2`、DNS `192.168.44.1`
- IPv4 TCP/UDP/DNSのユーザー空間転送
- 起動時の任意自動待受、connected-device foreground service
- ホーム・ログ・設定の下部ナビゲーションと、人間向けの簡易診断ログ
- 青を基調としたMaterial 3のライト／ダークUI、バージョン情報とオフラインライセンス画面
- 資格情報とAPNを伏せ、ATDは桁数・`*`・`#`・prefixを残す通常ログ
- 明示的に有効化した場合だけAT全文、PPP raw hex、IP/TCP/UDP詳細を残すデバッグログ
- RFCOMMセッション単位の永続ログ、5MB × 5ファイルのローテーション、過去ログ書き出し

IPv6、ICMP、IPフラグメント、複数同時DUNクライアントは対象外です。通常のT-Connect通信で必要になった実測結果が出た場合に追加します。

Android公開APIではDUN 1.2が要求する完全なSDP属性とBluetooth Class-of-Deviceをアプリから設定できません。端末・OS・ナビの組み合わせによっては、RFCOMM接続前のサービス発見で接続できない可能性があります。この制約だけを回避するためのhidden APIやrootは使用しません。

プロトコル根拠、既知の非準拠、状態遷移、実車での判定方法は次を参照してください。

- [プロトコル監査](docs/protocol-audit.md)
- [DUN/PPP/TCP状態遷移](docs/dun-state-machine.md)
- [T-Connectナビ実車テストチェックリスト](docs/vehicle-test-checklist.md)

## 実車確認

2026年9月22日、T-Connectナビのdocomo・au・SoftBank各キャリア設定で、RFCOMM接続、AT初期化、LCP/IPCP、DNS応答、PPP IPv4送受信、TCP双方向通信を確認しました。ナビやAndroid端末の機種・OSによってSDPやBluetoothの挙動が異なる可能性はあります。

## ビルド

Android Studioでプロジェクトを開くか、PowerShellで次を実行します。JDK 17以上とAndroid SDK Platform 37.0、Build Tools 36.0.0が必要です。

```powershell
cd C:\path\to\_AndroidApps\DUNavi
.\gradlew.bat :app:scribeLicensesDebugCheck :app:scribeLicensesReleaseCheck testDebugUnitTest lintDebug assembleDebug
```

APKは `app\build\outputs\apk\debug\app-debug.apk` に生成されます。

通常CIはpush・pull request・手動実行で検証し、`v*`タグからは既存release鍵で署名したAPKとSHA-256をGitHub Releaseへ公開します。署名Secretsの登録とタグ／バージョンの一致が必要です。詳細は [リリース手順](docs/releasing.md) を参照してください。

## 検証上の注意

ユニットテストで、T-Connect legacy AT fixture、AT sanitize、PPP framing/FCS/raw logging、LCP/IPCP、TCP送信queue、IPv4/UDP packet codec、通知更新判定、セッションログ分離、ライセンス一覧を確認します。Compose UIテストでは画面切替・情報画面・設定スイッチを確認します。実車では2026年9月22日に3キャリア設定のT-Connectセンター通信を確認済みですが、UI刷新後の実車回帰は個別に必要です。まず停車中に試し、運転中に端末を操作しないでください。

## ライセンス

Copyright (c) 2026 Tomoya Ogawa. MIT License。

パケット処理の一部はApache License 2.0のFreeProxyを改変して使用しています。詳細は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) を参照してください。
