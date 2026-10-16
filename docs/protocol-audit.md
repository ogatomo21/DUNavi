# DUNavi protocol audit

監査日: 2026-09-20（実車確認更新: 2026-09-22）  
対象: DUNavi 1.0.0 / `targetSdk = compileSdk = 37`  
目的: 完全準拠そのものではなく、T-Connectナビを Bluetooth DUN Data Terminal として安定して接続する。

## 結論

- 旧実装は、LCP/IPCP の片方向だけが成立した時点で次段へ進み、未知 option も ACK していた。これは [RFC 1661 §3.4, §5.1–5.4](https://www.rfc-editor.org/rfc/rfc1661.html) と [RFC 1332 §2](https://www.rfc-editor.org/rfc/rfc1332.html) に反し、PPP 確立失敗の直接原因になり得る。双方向の Configure-Ack を別々に管理し、Nak/Reject、ID・内容一致、再送 timer を最小実装した。
- AT 初期化列が狭すぎ、無害な V.250 初期化 command に `ERROR` を返していた。DUN 1.2 が列挙する基本 command と 3GPP packet-domain dial を追加し、未知 command の全面無条件 ACK は行わず、互換 no-op を明示リストに限定した。
- TCP proxy は zero window を MSS へ水増しし、部分 ACK、常時再送、FIN 再送、32-bit wrap を正しく扱わなかった。外部 dependency や全面的な TCP stack 化を避け、送信 queue の根本処理だけを修正した。
- Android 公開 API には SDP 制約がある。`listenUsingRfcommWithServiceRecord()` は secure RFCOMM と UUID/name/channel の SDP record を作るが、DUN 1.2 必須の `BluetoothProfileDescriptorList` と Class-of-Device の Telephony/Networking bit をアプリから設定する公開 API は確認できなかった。確認済みのT-ConnectナビではRFCOMM接続に成功したが、別のナビやAndroid端末での互換性は保証できない。

## 調査資料

一次資料として以下を使用した。

- Bluetooth SIG: [Dial-up Networking Profile 1.2](https://www.bluetooth.com/specifications/specs/dial-up-networking-profile-1-2/)、[1.1](https://www.bluetooth.com/specifications/specs/dial-up-networking-profile-1-1/)、[DUN Test Suite](https://files.bluetooth.com/wp-content/uploads/2024/10/DUN.TS_.p11.pdf)、[Serial Port Profile 1.2](https://www.bluetooth.com/specifications/specs/serial-port-profile-1-2/)、[Assigned Numbers](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html)
- Android: [`BluetoothAdapter.listenUsingRfcommWithServiceRecord`](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter)、AOSP の [generic RFCOMM SDP record 実装](https://android.googlesource.com/platform/system/bt/+/a51aaf236657d5ba04d1dbdac3f5c5eb0954bf91/btif/src/btif_sock_sdp.cc)
- ITU-T: [V.250](https://www.itu.int/rec/T-REC-V.250/en)
- 3GPP/ETSI: [TS 27.007 v18.8.0](https://www.etsi.org/deliver/etsi_ts/127000_127099/127007/18.08.00_60/ts_127007v180800p.pdf)
- IETF: [RFC 1661](https://www.rfc-editor.org/rfc/rfc1661.html)、[RFC 1662](https://www.rfc-editor.org/rfc/rfc1662.html)、[RFC 1332](https://www.rfc-editor.org/rfc/rfc1332.html)、[RFC 1877](https://www.rfc-editor.org/rfc/rfc1877.html)、[RFC 1334](https://www.rfc-editor.org/rfc/rfc1334.html)、[RFC 1994](https://www.rfc-editor.org/rfc/rfc1994.html)、[RFC 9293](https://www.rfc-editor.org/rfc/rfc9293.html)、[RFC 1122](https://www.rfc-editor.org/rfc/rfc1122.html)
- T-Connect: [携帯接続ご利用ガイド](https://toyota.jp/pages/contents/tconnectservice/contents/pdf/userguide_mobileconnect.pdf)、[接続方法](https://toyota.jp/tconnectservice/intro/connection.html)、[携帯接続の終了時期](https://toyota.jp/pages/contents/tconnectservice/contents/pdf/mobile_connection_plan.pdf)、[メーカーオプションナビ対象一覧](https://toyota.jp/pages/contents/tconnectservice/contents/pdf/car_list_an_mop.pdf)

DUN 公式ページから案内される ICS、IXIT、TCRL の個別 download は調査時に取得できなかった。この監査は profile 1.1/1.2 本文と公開 Test Suite を根拠とし、取得できなかった資料を推測で補っていない。T-Connect公開資料には実際の AT trace、PPP option、利用 protocol の packet capture はない。

## 適合性の表記

- **一致**: 現在のコードで引用 requirement を満たす。
- **仕様違反を修正**: 旧挙動が規格と不一致で、今回のテストと変更で修正した。
- **意図的互換動作**: 規格上の通常経路外だが、古い dialler を拒絶しないため安全な範囲で許容する。
- **公開 API 制約**: requirement は判明しているが、方針内の Android API では表現不能。
- **実車未知**: 規格違反とは断定できず、T-Connect の実装依存で実車だけが判定できる。

## Bluetooth DUN / RFCOMM / SDP

| Specification / section | 要求 | 現在の実装・挙動 | 判定 | T-Connectナビのリスク | 最小対応 / test |
|---|---|---|---|---|---|
| [DUN 1.2](https://www.bluetooth.com/specifications/specs/dial-up-networking-profile-1-2/) §2.2–2.4 | GW は network access/DCE、DT は DTE。DT が接続を開始 | `DunService` が DUN GW UUID で listen。ナビが RFCOMM を開始し、アプリが AT/PPP/IP proxy を提供 | 一致 | 低 | 実車で `RFCOMM CONNECTED` を確認 |
| [Bluetooth Assigned Numbers](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html) Service Class UUID | DUN Service Class UUID は `0x1103` | UUID `00001103-0000-1000-8000-00805F9B34FB` | 一致 | 低 | source test/実車 SDP browse |
| [DUN 1.2](https://www.bluetooth.com/specifications/specs/dial-up-networking-profile-1-2/) §5.3, [DUN TS](https://files.bluetooth.com/wp-content/uploads/2024/10/DUN.TS_.p11.pdf) SDP tests | ServiceClassIDList、L2CAP/RFCOMM/channel の ProtocolDescriptorList、DUN 1.2 の BluetoothProfileDescriptorList が必須 | Android の generic service record は UUID/name/channel と L2CAP/RFCOMM を作る。AOSP generic path には profile descriptor の設定が見当たらない | **公開 API 制約** | **高/P0**。ナビが ProfileDescriptorList を厳格検査すると発見不能 | hidden API/root は使わない。実車 SDP discovery を最初に判定。端末/OS差も記録 |
| [DUN 1.2](https://www.bluetooth.com/specifications/specs/dial-up-networking-profile-1-2/) §5.5.1 | active GW は CoD Telephony と Networking service bit を set | 公開 Android API で任意 CoD bit を set しない | **公開 API 制約** | 中～高/P0 | 実車が UUID discovery を採用するか確認 |
| Android `listenUsingRfcommWithServiceRecord` | authenticated/encrypted secure RFCOMM、system assigned channel、SDP UUID/name | secure API を利用。channel は OS に委任 | 一致 | 低 | ペアリング後接続、再接続、切断を実車確認 |
| [DUN 1.2](https://www.bluetooth.com/specifications/specs/dial-up-networking-profile-1-2/) §6.1–6.3 | discoverable/connectable/pairable、authentication 必須、DT 起点 pairing | UI に discoverable 導線、secure RFCOMM。pairing は Android system UI | 一致。ただし discoverable duration/OEM 挙動は実車未知 | 中 | 未pairing/paired/reboot後の3ケース |
| [DUN 1.2](https://www.bluetooth.com/specifications/specs/dial-up-networking-profile-1-2/) §2.4 | clean disconnect と link loss | socket close を検知して router/session を終了し listen に戻る | 一致 | 中 | ナビ切断、BT OFF、車両電源 OFF で再待受を確認 |

## AT modem emulation

| Specification / section | 要求 | 旧実装 → 現在の実装 | 判定 | T-Connectナビのリスク | test |
|---|---|---|---|---|---|
| [DUN 1.2](https://www.bluetooth.com/specifications/specs/dial-up-networking-profile-1-2/) §4.1.2 | `&C,&D,&F,+GCAP,+GMI,+GMM,+GMR,D,E,H,L,M,O,P,Q,S0,S10,S3,S4,S5,S6,S7,S8,T,V,X,Z` を mandatory として列挙 | 少数 exact command のみ → compound basic command と列挙 command/query の安全な subset を実装 | 仕様違反を修正。ただし完全な modem semantics ではない | 高/P0 | compound init、Q/V、S register、identity query |
| [DUN 1.2](https://www.bluetooth.com/specifications/specs/dial-up-networking-profile-1-2/) §4.1.3 | `OK, CONNECT, NO CARRIER, ERROR, NO DIALTONE, BUSY` result code | `OK/CONNECT/ERROR` と verbose/numeric/quiet。実回線 dial failure 状態を持たないため残りは生成しない | 既知の部分非準拠 | 中 | Q/V mode と CONNECT を fixture test。実車が失敗 result を要求するか確認 |
| [ITU-T V.250](https://www.itu.int/rec/dologin_pub.asp?id=T-REC-V.250-200307-I%21%21PDF-E&lang=e&type=items) §5.2.1–5.2.3, §5.5–5.7, §6.1.1–6.1.9, §6.2.1–6.2.9 | `AT` prefix、basic command の連結、S-register、echo/quiet/verbose、result formatting | stateful parser。CR termination、backspace、compound command、S3/S4/S5、E/Q/V | 概ね一致 | 中 | `AT&F0E0V1Q0X4&C1&D2S0=0` fixture |
| [3GPP TS 27.007](https://www.etsi.org/deliver/etsi_ts/127000_127099/127007/18.08.00_60/ts_127007v180800p.pdf) §10.2.1.1–10.2.1.2 | `D*99...#` / `D*98...#` packet-domain dial、CONNECT 後 online data | 標準形に加え、RFCOMM上のlegacy DUNでは通常の番号ATDも非音声データ要求としてPPPへ遷移。末尾`;`だけはvoice dialとしてOK、途中`;`はERROR。starなし `D99#` もlegacy alias | 一致 + 意図的互換動作 | 高/P0 | T-Connectのキャリア設定別dial形式をsanitized logで確認 |
| T-Connect/legacy handset vendor command | `AT@AUBTSET?` / `AT@AUBTSET=0` の公開標準仕様は確認できない | T-Connect/legacy compatibility commandとしてqueryと数値assignmentに明示 `OK`。未知の `AT@` は `ERROR` | 実車依存の意図的互換動作 | 高/P0 | 初期化列で `ERROR` が消え、直後のATD形式を確認 |
| [3GPP TS 27.007](https://www.etsi.org/deliver/etsi_ts/127000_127099/127007/18.08.00_60/ts_127007v180800p.pdf) §7.2, §8.5, §10.1.1, §10.1.9, §10.1.12, §10.1.20 | `+CREG`, `+CSQ`, `+CGDCONT`, `+CGATT`, `+CGDATA`, `+CGREG` | 最小の set/query/test response。APN/値はログで伏せる | legacy 向け最小実装 | 高/P0 | 各 command、APN 非表示を test/目視確認 |
| [ITU-T V.250](https://www.itu.int/rec/dologin_pub.asp?id=T-REC-V.250-200307-I%21%21PDF-E&lang=e&type=items) §5.6 | 未認識 command は実行を中止し `ERROR`。実装依存 init を全面 ACK する根拠はない | 以前は全て ERROR。現在は `+CGQREQ/+CGQMIN/+CGEREP/+CGAUTO/+CGACT/+CGCLASS` の set のみ明示 no-op OK、その他は ERROR | **意図的互換動作** | 中 | 実車 trace で必要 command が判明した時だけ allowlist 追加 |

厳密モードと legacy mode を UI switch にはしていない。PPP core は規格に従い、AT だけを「副作用がなく応答内容を参照されにくい command」の明示 allowlist で寛容にした。未知 command を無条件 `OK` にすると、ナビに存在しない能力を誤認させるため行わない。

## PPP framing / LCP / authentication / IPCP

| Specification / section | 要求 | 旧実装 → 現在の実装 | 判定 | T-Connect リスク | test |
|---|---|---|---|---|---|
| RFC 1662 §3–4, Appendix C | flag/escape/FCS、受信 ACCM の control octet を FCS 計算前に除去 | framing/FCS は既存。unescaped control を残していた → default receive ACCM として除去、dangling escape/oversize/bad FCS を破棄・記録 | 仕様違反を修正 | 高/P1 | RFC 風 fixture、bad FCS、malformed、ACCM |
| RFC 1661 §3.4 | 双方が Configure-Request を送信し、双方の request が Ack されて Opened | peer request ACK 直後にも IPCP 開始 → local/peer Ack を独立管理 | 仕様違反を修正 | **高/P1** | peer先行、local先行、正常 handshake |
| RFC 1661 §5.1–5.4 | Ack は同じ identifier と option 内容。Nak/Reject では新 request | code を問わず IPCP 開始 → ID/内容一致を確認し、Nak replacement/Reject removal で再要求 | 仕様違反を修正 | **高/P1** | bad Ack、Nak、Reject fixture |
| RFC 1661 §6 | unsupported option は Configure-Reject、受理不能値は Nak | 全 option ACK → MRU/ACCM/Magic/PFC/ACFC のみ検証し、auth/quality/unknown を Reject | 仕様違反を修正 | 高/P1 | unknown Reject、malformed、MRU |
| RFC 1661 §6.1 | peer MRU を超える Information field を送らない | MRU 無視 → negotiated peer MRU を保持し、大きい IPv4 を送信しない。TCP MSS も link MRU から算出 | 仕様違反を修正 | 中/P1 | 128-byte MRU fixture |
| RFC 1662 §7.1–7.2 | ACCM/PFC/ACFC は LCP negotiation に従う | option を無視 → 妥当な option を ACK。送信は常に全 control escape・uncompressed、受信は圧縮/非圧縮双方 | 一致。negotiated compression の使用は optional | 中 | ACCM/PFC/ACFC fixture |
| RFC 1661 §4.6 | Configure-Request は Restart timer で再送 | one-shot → 3秒、最大10回 | 仕様違反を修正 | 高/P1 | fake clock retransmission |
| RFC 1661 §5.5–5.10 | Terminate/Echo 等 | Terminate Ack、Echo Reply。完全な Code-Reject/Protocol-Reject/full automaton は未実装 | 部分準拠 | 中/P3 | Echo/Terminate、実車 loss/reconnect |
| RFC 1334 §2 | PAP は LCP Authentication-Protocol で negotiated 後に Authenticate-Request/Ack | 旧実装は無条件 ACK。現在も LCP auth option は Reject する一方、well-formed PAP request は credential を記録せず ACK | **意図的互換動作**（厳密には通常経路外） | 古い dialler には有利、認証必須 peer には不足 | PAP fixture。実車が PAP option を要求するか確認 |
| RFC 1994 | CHAP challenge/response は negotiated algorithm と challenge が必要 | unsolicited response に Success を返していた → auth option を Reject し、unsolicited CHAP は無応答 | 仕様違反を修正 | 低～中 | CHAP ignore fixture。実車が CHAP 必須なら追加設計が必要 |
| RFC 1332 §2 | IPCP は LCP Opened 後、IP traffic は IPCP Opened 後 | LCP片方向で開始、IPCP片方向で network open → 両方向成立後だけ IPv4 | 仕様違反を修正 | **高/P1** | IPCP local/peer 双方向 test |
| RFC 1332 §3.1–3.3 | IP-Addresses/IP-Compression/IP-Address option。未知/非対応は Reject | 未知も ACK → IP-Address(3) を NAK/ACK、compression/unknown を Reject | 仕様違反を修正 | 高/P1 | unknown option、address NAK |
| RFC 1877 §1–2 | DNS option 129/131 を Nak で提案可能 | `192.168.44.1` を primary/secondary DNS として Nak、再要求を Ack | 一致 | 高/P1 | DNS negotiation fixture |

### PPP に残る制限

- RFC 1661 の完全な event/state table、Code-Reject、Protocol-Reject、Failure counter、Opened 後の再 Configure は未実装。現在の peer が行う通常の初期 handshake には不要だが、実車ログで要求された場合は P3 として追加する。
- PAP は credential を検証せず、legacy dialler を通すための workaround である。ローカル Bluetooth pairing 済み peer に限定されるが、厳密 PAP authenticator ではない。
- CHAP は negotiation/challenge を提供しない。実車が LCP Authentication-Protocol=CHAP を必須にする証拠が出た時だけ実装する。
- outgoing IPv4 が peer MRU を超えた場合は fragmentation せず drop する。

## IPv4 / TCP / UDP / DNS

| Specification / section | 要求または観点 | 旧実装 → 現在の実装 | 判定 | T-Connect リスク | test |
|---|---|---|---|---|---|
| RFC 9293 §3.8.6.1 / RFC 1122 §4.2.2.17 | advertised receive window を尊重。zero window では probe | 0/小windowをMSSへ水増し → 正確に制限し、zero-window probe timer | 仕様違反を修正 | 高/P2 | zero/small window fixture |
| RFC 9293 §3.10.8 | outstanding segment の retransmission timer | write待ち時だけ → SYN/data/FIN を queue に置き独立 timer | 仕様違反を修正 | 高/P2 | fake clock retransmission |
| RFC 9293 ACK processing | cumulative/partial/duplicate/future ACK を安全に扱う | partial ACK 後も既ACK prefixを保持 → prefix trim、duplicate/future無視 | 仕様違反を修正 | 中/P2 | partial/duplicate/future ACK |
| RFC 9293 §3.4, §3.6 | SYN/FIN は sequence space を1消費し、重複・再送を処理 | SYN/FIN再送とFIN ACK cleanupが不足 → queue化、duplicate SYN/FIN応答、双方FIN後cleanup | 仕様違反を修正 | 高/P2 | FIN、retransmit、duplicate |
| RFC 9293 §3.4.1 | 32-bit serial arithmetic | 単純比較 → modulo arithmetic | 仕様違反を修正 | 低/P3 | wraparound fixture |
| userspace TCP proxy | root/VPNなしでは raw NAT を使えない | ナビ側 TCP を終端し、Java `Socket` で別接続。fixed RTO、単純window、SACK/congestion control/TIME_WAITなし | 実装上の意図的制限 | 中 | 実車 HTTPS/長時間/再接続 test |
| UDP session lifecycle | 同一 4-tuple の単一 session | concurrent create で loser socket leak → `putIfAbsent` loser close | 実装不具合を修正 | 低～中/P2 | race は code inspection。実車連続 DNS |
| RFC 1877 + DNS | negotiated virtual DNS を upstream へ転送 | UDP/53 を Android active network DNS へ proxy。成功/timeout/failure log | 一致する通常経路 | 中 | DNS query/reply log。TCP fallback は未実装 |
| IPv4 fragmentation | 大きな datagram に必要な場合がある | fragment/reassembly なし。fragment は実質 drop | **実車未知**、既知未実装 | 中/P3 | packet capture で fragment が観測された時だけ実装 |
| ICMP | PMTU/error/ping に利用され得る | ICMP proxy なし | **実車未知**、既知未実装 | 低～中/P3 | T-Connect失敗時にICMP依存をcapture確認 |

TCP proxy は「完全な標準 TCP endpoint」ではない。一般的な T-Connect の DNS と HTTPS を成立させるための最小実装であり、実車で必要性が出ていない congestion control、SACK、timestamps、IPv6、ICMP、fragmentation を追加していない。

## Boot / Android lifecycle

| 資料 / section | 要求・制約 | 実装 | 判定 / リスク |
|---|---|---|---|
| Android [background FGS start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) | `BOOT_COMPLETED` は background start exemption。新しい Android では一部 service type の boot start 制約あり | user opt-in の時だけ `BootReceiver` が `connectedDevice` FGS を開始。BT permission がなければ skip を記録 | 公開 API 内。API 37/OEM の実機挙動は未知 |
| Android [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) | connected-device type と関連 permission | manifest に `FOREGROUND_SERVICE_CONNECTED_DEVICE`、runtime `BLUETOOTH_CONNECT` | 一致 |

## 優先度と変更範囲

| 優先度 | 内容 | 対応 |
|---|---|---|
| P0 | DUN discovery を妨げる SDP/CoD 制約 | 公開 API では修正不能。実車の最初の判定点として文書化 |
| P0 | DUN mandatory/典型 AT 初期化と packet dial | `AtModem` の parser/応答だけを拡張 |
| P1 | LCP/IPCP の早期遷移、option、timer、MRU、framing | `PppSession/PppDecoder` のみ修正 |
| P2 | TCP window/retransmit/FIN/ACK、UDP race | 既存 proxy の queue/lifecycle のみ修正 |
| P3 | full PPP FSA、DNS/TCP、ICMP、fragment | 実車証拠がないため未実装 |
| P4 | 大規模 refactor/汎用 stack 化 | 行わない |

## 修正前後のテスト

- 修正前 baseline: 既存 8 test は成功。
- 仕様 fixture を先に追加した時点: 23 test 中 13 test が失敗。主因は LCP/IPCP 早期遷移、unknown option ACK、ACCM、PAP/CHAP、TCP window/ACK/retransmit/FIN/wrap。
- 現在: Debug/Releaseそれぞれ63 test が成功。対象は T-Connect legacy scenario fixture、AT compound/legacy/sanitize、PPP framing/FCS/malformed/raw logging、LCP peer/local/Nak/Reject/options/MRU/ACCM/PFC/ACFC/timer/PAP/CHAP、IPCP/DNS、TCP SYN/zero-window/partial ACK/duplicate ACK/retransmit/FIN/wrap、packet codec、通知更新判定、セッションログ分離/rotation/export、ライセンス一覧。

fixture は production encoder 同士の round-trip だけにせず、control packet と option byte列を test 側で直接構築した。

## Android 公開 API では対応不能または保証不能な事項

1. arbitrary SDP attributes、特に DUN `BluetoothProfileDescriptorList` version 1.2 の追加。
2. Bluetooth Class-of-Device の Telephony/Networking service bit の設定。
3. OS が割り当てた RFCOMM channel や SDP database の vendor 実装差の排除。
4. ナビ側の SDP filter 条件、pairing/security policy の制御。
5. BOOT_COMPLETED 後の OEM 独自省電力制限の回避保証。

これらのためだけに hidden API、reflection、root、system Bluetooth stack 改変は導入しない。

## T-Connectナビ実車確認

実車試験で、docomo・au・SoftBankの各キャリア設定について次を確認した。

1. generic Android SDP record からDUN GWを発見し、secure RFCOMM接続が成立する。
2. キャリア設定ごとに異なるAT初期化列とdial stringから`CONNECT`へ進む。
3. LCP/IPCPが開き、ナビへIPv4アドレスとDNSを提供する。
4. DNS queryを上流へ転送し、transaction IDと送信元を対応付けた応答をPPP peerへ返す。
5. `PPP/TX protocol=IPv4`とRFCOMM writeが発生し、TCPの接続・データ・FINを双方向転送する。

別のT-ConnectナビやAndroid端末では、SDP実装差、長時間通信、再接続、端末再起動後の自動待受、OEM省電力制限を個別に確認する。

判定手順は [vehicle-test-checklist.md](vehicle-test-checklist.md)、通信順序は [dun-state-machine.md](dun-state-machine.md) を参照する。
