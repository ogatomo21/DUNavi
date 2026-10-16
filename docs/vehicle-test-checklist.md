# T-Connectナビ 実車テストチェックリスト

停車し、パーキングブレーキを使用した状態で行う。運転中に Android 端末やナビを操作しない。

## 事前確認

- [ ] 車両年式、ナビ型番、メーカーオプション/販売店装着の別を記録した。
- [ ] Toyota 公式の対象一覧と終了時期を確認し、そのナビ/契約で携帯接続を現在利用できる。
- [ ] Android とナビを system Bluetooth 設定で一度削除し、必要なら新規 pairing できる。
- [ ] DUNavi に Bluetooth 接続、付近デバイス、通知の権限がある。
- [ ] Android 自身がモバイル回線または Wi-Fi で DNS/HTTPS 通信できる。
- [ ] battery optimization/OEM自動起動制限の有無を記録した。最初の試験はアプリを画面から起動する。
- [ ] APN、電話番号、username/password、車両識別情報をログや issue に投稿しない。

## Logcat の取得

PowerShell で adb が使える PC に接続する。

```powershell
adb devices
adb logcat -c
adb logcat -v threadtime -s DUNavi:I '*:S' | Tee-Object -FilePath .\dunavi-vehicle.log
```

試験後は `Ctrl+C` で停止する。DUNavi は通常モードで APN/credential 値を伏せ、ATDは数字を `d` に置換しつつ `*`、`#`、桁数、`T/P` prefix、`;` を残す。設定タブのデバッグモードを有効にすると、build種別にかかわらずAT全文、PPP raw hex、通信先などの詳細を記録するため、書き出し・共有時は機微情報を確認する。Bluetooth HCI snoop log は SDP/RFCOMM の最終診断に有用だが、link payload や識別情報を含み得るため、通常試験では有効化せず、必要時だけ端末の開発者向けオプションから取得して安全に保管する。

## 試験手順と期待ログ

| 手順 | 期待ログ | NG の判定 |
|---|---|---|
| DUNavi で待受開始 | `DUN待受開始 (secure RFCOMM)` | permission/adapter/listen error は Android 側 |
| ナビで Bluetooth 携帯接続を検索/選択 | `BT RFCOMM CONNECTED device=...` | 出なければ SDP/CoD、pairing、ナビのDUN対応/設定 |
| ナビでセンター接続 | `AT/RX ...`, `AT/TX OK` | `ERROR` 直前のcommandと、ATDのsanitized形式（`*`、`#`、桁数、prefix、`;`）を記録 |
| packet dial | `AT/TX CONNECT` | dial string が対応範囲外か、前段 init failure |
| PPP LCP | peer/local request、`LCP OPENED` | FCS、option Reject/Nak loop、Ack不一致 |
| PPP IPCP | address/DNS Nak/Ack、`NETWORK OPEN` | IP/DNS option、片方向Ack、再送上限 |
| 名前解決 | `DNS/RX query received` → `upstream response` → `DNS/TX IPv4 response built` → `PPP/TX protocol=IPv4` | Android active DNS、UDP response、PPP return path |
| センター TCP | `TCP/RX ... flags=SYN`、`TCP/TX ... flags=SYN\|ACK`、`TCP ... ESTABLISHED` | remote reachability、window/ACK/retransmit |
| 切断 | `TCP ... FIN/RST`, `RFCOMM DISCONNECTED` | session leak/ナビ側異常切断 |

成功条件は、単に Bluetooth 接続表示が出ることではなく、`NETWORK OPEN`、DNS応答のPPP送信、センター向けTCP `ESTABLISHED`が順番に確認でき、ナビのオンライン機能が応答することである。

## 失敗時に保存する最小情報

- DUNavi version、Android端末機種、Android version。
- 車両年式とナビ型番。Bluetooth device address、車台番号、契約IDは保存しない。
- 上記の layer checkpoint と、最初の unexpected `ERROR`/Nak/Reject/timeout の前後20行。
- 再現時刻、モバイル/Wi-Fi、初回pairingか再接続か、起動直後か。
- 問題が SDP discovery 前なら、Android端末を変えた結果。Android OS/vendorの SDP 差を切り分ける。

追加の read-only 確認:

```powershell
adb shell dumpsys bluetooth_manager
adb shell dumpsys activity services net.ogatomo.dunavi
```

## 回帰ケース

- [ ] 未pairingからpairingし接続。
- [ ] pairing済みで再接続。
- [ ] ナビ側切断後、DUNaviが再待受。
- [ ] Android Bluetooth OFF/ON 後に再待受。
- [ ] 車両電源 OFF/ON 後に再接続。
- [ ] Android reboot後、「端末起動時に自動待機」が有効なら `BOOT auto-start` と待受を確認。
- [ ] 権限を外した reboot では crash せず `BOOT auto-start skipped`。
- [ ] 10分以上の通信、複数DNS query、複数HTTPS接続、ナビ画面遷移。
- [ ] zero-window/retransmit/FINが発生しても session が回復または終了する。

## 未実装機能を追加する判断基準

- DNS UDP response が truncated (`TC=1`) で、ナビが TCP/53 を開始して失敗する capture が得られた場合だけ DNS TCP fallback を追加する。
- ICMP error/echo が T-Connect の次段を実際に止めている capture が得られた場合だけ ICMP proxy を設計する。
- IPv4 fragment または peer MRU 超過が正常通信に必須と確認できた場合だけ fragmentation/reassembly を設計する。
- CHAP Authentication-Protocol が必須で Reject 後に peer が終了する trace が得られた場合だけ CHAP challenge/credential policy を設計する。

推測だけでこれらを追加すると、テスト面積と故障点を増やすため実装しない。
