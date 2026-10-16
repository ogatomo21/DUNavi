# DUNavi state machine

## End-to-end sequence

```mermaid
sequenceDiagram
    participant Nav as T-Connect Navi (DT/DTE)
    participant BT as Android Bluetooth stack
    participant Dun as DunService / AtModem (GW/DCE)
    participant PPP as PppDecoder / PppSession
    participant IP as IpRouter / TCP-UDP proxy
    participant Net as Android active network
    participant Center as DNS / T-Connect servers

    Dun->>BT: secure RFCOMM listen, DUN UUID 0x1103
    Note over BT: Generic Android SDP record<br/>DUN ProfileDescriptor/CoDは保証不能
    Nav->>BT: SDP discovery, pairing, RFCOMM connect
    BT->>Dun: BluetoothSocket accepted
    Dun-->>Nav: AT result codes (OK)
    Nav->>Dun: AT init / registration / packet setup
    Nav->>Dun: ATD*99...# or AT+CGDATA
    Dun-->>Nav: CONNECT
    Dun->>PPP: start, local LCP Configure-Request
    Nav->>PPP: peer LCP Configure-Request
    PPP-->>Nav: Ack / Nak / Reject
    Nav-->>PPP: exact Ack for local request
    Note over PPP: local ACKed AND peer ACKed => LCP OPENED
    opt legacy dialler sends PAP
        Nav->>PPP: PAP Authenticate-Request
        PPP-->>Nav: Authenticate-Ack (compatibility workaround)
    end
    PPP-->>Nav: local IPCP Configure-Request (192.168.44.1)
    Nav->>PPP: peer IPCP Configure-Request (IP/DNS)
    PPP-->>Nav: Nak with 192.168.44.2 and DNS 192.168.44.1
    Nav->>PPP: revised IPCP Configure-Request
    PPP-->>Nav: Configure-Ack
    Nav-->>PPP: exact Ack for local request
    Note over PPP: local ACKed AND peer ACKed => NETWORK OPEN
    Nav->>IP: IPv4 packet over PPP
    IP->>Net: Java Socket / DatagramSocket
    Net->>Center: Android default route
    Center-->>Net: response
    Net-->>IP: socket response
    IP-->>Nav: synthetic IPv4 TCP/UDP over PPP
```

## Service / AT states

```mermaid
stateDiagram-v2
    [*] --> Stopped
    Stopped --> Waiting: user start or enabled BOOT_COMPLETED
    Waiting --> AtCommand: secure RFCOMM accepted
    AtCommand --> AtCommand: safe init/query => OK/data
    AtCommand --> Ppp: D*99 / D*98 / +CGDATA => CONNECT
    AtCommand --> AtCommand: unsupported command => ERROR
    Ppp --> Waiting: socket close / Bluetooth loss
    Waiting --> Stopped: user stop
    Waiting --> Error: permission/adapter/listen failure
    Error --> Waiting: foreground service restart
```

`AtModem` の legacy tolerance は allowlist 方式である。`+CGQREQ`, `+CGQMIN`, `+CGEREP`, `+CGAUTO`, `+CGACT`, `+CGCLASS` の設定は副作用なしで `OK`、それ以外の未知 command は `ERROR` とする。

## LCP / IPCP states

```mermaid
stateDiagram-v2
    [*] --> LcpNegotiating: PPP start / local Configure-Request
    LcpNegotiating --> LcpNegotiating: peer Request => Ack/Nak/Reject
    LcpNegotiating --> LcpNegotiating: local Nak/Reject => revised Request
    LcpNegotiating --> LcpOpened: local request ACKed AND peer request ACKed
    LcpOpened --> IpcpNegotiating: local IPCP Configure-Request
    IpcpNegotiating --> IpcpNegotiating: peer Request => Ack/Nak/Reject
    IpcpNegotiating --> IpcpNegotiating: local Nak/Reject => revised Request
    IpcpNegotiating --> NetworkOpen: local request ACKed AND peer request ACKed
    NetworkOpen --> LcpNegotiating: new RFCOMM session
    NetworkOpen --> [*]: LCP/IPCP Terminate or RFCOMM close
```

同じ identifier でも option byte列が異なる Configure-Ack は受理しない。Configure-Request は 3 秒ごと、最大 10 回再送する。完全な RFC 1661 automaton（Opened 後の再configure、Code/Protocol-Reject、Failure counter）は未実装である。

## TCP proxy states

```mermaid
stateDiagram-v2
    [*] --> Connecting: nav SYN
    Connecting --> Connecting: duplicate SYN / SYN-ACK retransmit
    Connecting --> Established: Android Socket connected + SYN-ACK ACKed
    Connecting --> Closed: connect failure / RST
    Established --> Established: data, cumulative ACK, retransmit, zero-window probe
    Established --> Established: peer FIN => ACK + upstream shutdownOutput
    Established --> Established: upstream EOF => FIN (retransmit until ACK)
    Established --> Closed: peer FIN received AND local FIN ACKed
    Established --> Closed: RST / socket error / service stop
    Closed --> [*]
```

これはナビ側 TCP を終端して Android `Socket` へ中継する最小 proxy であり、raw NAT ではない。zero/small receive window、部分 ACK、32-bit wrap、SYN/data/FIN 再送を扱うが、SACK、timestamps、congestion control、完全な TIME_WAIT は提供しない。

## 一回の実車試験で見る境界

| 最後に見えたログ | 到達点 | 次に疑う箇所 |
|---|---|---|
| `DUN待受開始 (secure RFCOMM)` のみ | Android は待受中 | SDP ProfileDescriptor/CoD 制約、pairing、ナビ設定 |
| `RFCOMM CONNECTED` | Bluetooth/RFCOMM 成立 | AT command/parser |
| `AT RX...` だが `CONNECT` なし | modem init 途中 | 直前 command と result。値は共有時に伏せる |
| `LCP ...` だが `LCP OPENED` なし | PPP framing/LCP | Ack ID/content、Nak/Reject、option、FCS |
| `LCP OPENED` だが `NETWORK OPEN` なし | IPCP | address/DNS option と双方向 Ack |
| `NETWORK OPEN` だが DNS 成功なし | IPv4/DNS | upstream DNS、UDP、fragment/ICMP の packet capture |
| `DNS/TX IPv4 response built` だが TCP established なし | name resolution 後 | destination reachability、SYN/SYN-ACK/ACK |
| `TCP ... ESTABLISHED` | end-to-end TCP proxy 成立 | TLS/T-Connect application、契約/センター側 |

詳細は [vehicle-test-checklist.md](vehicle-test-checklist.md) を参照する。
