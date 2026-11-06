# SpeakerArray

Androidアプリ「SpeakerArray」の開発用リポジトリです。

リポジトリ: [hebowiz/SpeakerArray](https://github.com/hebowiz/SpeakerArray)

## プロジェクトの作成

現在はGitの初期設定のみを配置しています。Androidアプリ本体は未作成です。

Android Studioでプロジェクトを作成する際は、このリポジトリのルートを保存先に指定してください。生成された`.gitignore`に、このリポジトリの除外設定も残してください。

プロジェクト作成後は、ソースコードとビルド設定に加え、`gradlew`、`gradlew.bat`、`gradle/wrapper/`もコミットします。`gradlew`には、次のコマンドでGit上の実行権限を設定します。

```powershell
git add gradlew
git update-index --chmod=+x gradlew
```

SDKのバージョン、ビルド手順、テスト手順、GitHub Actionsは、アプリの構成が決まった段階で整備します。

## Gitで管理するファイル

`.gitignore`で、ビルド成果物、キャッシュ、IDE設定、`local.properties`、署名鍵、ローカルの秘密情報ファイルを除外しています。認証情報を共有するビルド設定やソースコードに直接書き込まないでください。

`.gitattributes`で改行コードを指定しています。`gradlew`などのシェルスクリプトはLF、Windows用のバッチファイルはCRLFを使用します。
