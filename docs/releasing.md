# GitHubリリース手順

DUNaviは単独リポジトリ `ogatomo21/DUNavi` として公開する構成です。このディレクトリをリポジトリのルートにしてください。

## 1. ビルド環境

- JDK 17以上（GitHub ActionsはTemurin 17）
- プロジェクトに同梱したGradle Wrapper
- Android SDK Platform 37.0、Build Tools 36.0.0

PowerShellでのローカル検証:

```powershell
.\gradlew.bat :app:scribeLicensesDebugCheck :app:scribeLicensesReleaseCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

通常CIはブランチへのpush、pull request、手動実行で動きます。Debug APK、UIテストAPK、テスト・LintレポートをActions Artifactへ14日間保存します。CIでは署名鍵を使用しません。

UI操作テストは起動したエミュレータで `.\gradlew.bat connectedDebugAndroidTest` を実行します。Android 17ではEspresso 3.5系の入力APIが動かないため、テスト用依存には安定版3.7.0を明示しています。アプリ本体の依存には含まれません。

## 2. 既存のrelease署名鍵を登録

継続配布には同じ鍵を使います。リポジトリの **Settings → Secrets and variables → Actions** へ次を登録してください。

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | 既存keystoreファイルのBase64表現 |
| `KEYSTORE_PASSWORD` | keystoreのパスワード |
| `KEY_ALIAS` | 署名鍵のalias |
| `KEY_PASSWORD` | 署名鍵のパスワード |

GitHub CLIでkeystoreを登録する例（ファイルのパスは実際の保管場所へ変更）:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\keys\dunavi-release.jks')) |
    gh secret set KEYSTORE_BASE64 --repo ogatomo21/DUNavi
gh secret set KEYSTORE_PASSWORD --repo ogatomo21/DUNavi
gh secret set KEY_ALIAS --repo ogatomo21/DUNavi
gh secret set KEY_PASSWORD --repo ogatomo21/DUNavi
```

パスワードはGitHub CLIの入力プロンプトで指定します。鍵・パスワード・Base64文字列はソースやIssueへ貼り付けないでください。GHAでは一時ディレクトリへ鍵を復元し、ビルド後に削除します。Secrets不足・署名検証失敗時はReleaseを公開しません。

ローカルreleaseビルドでは、`KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`をプロセスの環境変数へ設定した上で実行します。

```powershell
.\gradlew.bat testReleaseUnitTest lintRelease assembleRelease
```

署名情報を設定していない場合、`assembleRelease`はエラーになります。Debug鍵への自動切替はありません。

## 3. バージョンを更新

`app/build.gradle.kts` の `versionName` と `versionCode` を更新します。

- 初期値: `versionName = "1.0.0"`、`versionCode = 1`
- 次の配布では`versionCode`を必ず増やします。
- タグは`v`と`versionName`を結合した名前です。例: `v1.0.0`。
- プレリリースは`versionName = "1.1.0-beta.1"`、タグ`v1.1.0-beta.1`のように揃えます。

リリース前に [実車テストチェックリスト](vehicle-test-checklist.md) を確認します。既存のdocomo・au・SoftBank設定、再接続、画面OFF、自動待受、通知の停止操作を確認してください。

## 4. タグで公開

ソースをGitHubへ反映した後、対象コミットへタグを付けてpushします。

```powershell
git tag v1.0.0
git push origin v1.0.0
```

Release workflowは以下を順に実施します。

1. タグとGradleの`versionName`の一致確認
2. Debug/Release単体テスト、Lint、ライセンスカタログ検証
3. 既存release鍵によるAPKビルド
4. `apksigner verify`による署名検証
5. `DUNavi-v1.0.0.apk`と`SHA256SUMS.txt`の生成
6. 自動生成リリースノートとともにGitHub Releaseへ添付

`-beta.1`等を含むタグはGitHub上のプレリリースとして公開します。APKは単一のuniversal APKです。AAB・ABI分割・R8・resource shrinkingは現在使用していません。

手動で再実行する場合は **Actions → Android Release Build and Publish → Run workflow** から、既存タグを指定してください。ブランチ名は受け付けません。公開ジョブだけが`contents: write`を使用します。

## 5. 配布物と更新互換性を確認

```powershell
Get-FileHash .\DUNavi-v1.0.0.apk -Algorithm SHA256
adb install -r .\DUNavi-v1.0.0.apk
```

ハッシュを添付の`SHA256SUMS.txt`と照合します。既存release版への上書き後に、自動待受設定、保存ログ、端末プロファイルが残ることを確認してください。署名が異なるDebug版からは上書きできません。更新できない場合も、データを保持したい端末でアンインストールを先に実行しないでください。

## ライセンスカタログの更新

License Scribeのcore plugin/runtimeで依存ライブラリ一覧を生成します。カタログは `app/licenses/debug/` と `app/licenses/release/` に保存し、生成されたKotlinソースはGit管理しません。

依存変更後は次を実行し、カタログの差分と出典を確認します。

```powershell
.\gradlew.bat :app:scribeLicensesDebugSync :app:scribeLicensesReleaseSync
.\gradlew.bat :app:scribeLicensesDebugCheck :app:scribeLicensesReleaseCheck
```

新しいライセンス種別が増えた場合は本文をオフラインassetsへ追加し、`licenseAsset()`の対応と単体テストを確認します。FreeProxyはソースを取り込んでいるため、Gradleカタログとは別に手動表記しています。本アプリのMIT本文とFreeProxyの通知文は、ルートの `LICENSE` と `THIRD_PARTY_NOTICES.md` からビルド時に同梱されます。
