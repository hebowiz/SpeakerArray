# 品質判定と分けてFIRを試聴するPoC

「フィルタ自動設計」で測定から試聴用FIRの作成まで進み、通常再生へ手動反映できるようにしました。品質不足、未比較、予測上の悪化、実測3 dB未達でも反映できます。従来の「自動調整」は正式検証を続けます。

## 操作

1. 「フィルタ自動設計」で「広帯域FIR」を選び、出力とMICを確認します。初回は「主試験帯域 300〜3,400 Hz」を押し、左右の確認を済ませます。現在の帯域・音量を測定に使い、自動変更はしません。
2. 「測定してFIRを作成」を押します。左右各2回の応答から集約候補を作り、選択状態にします。診断JSONを保存・編集・再読込する必要はありません。比較測定は行いません。
3. 「FIRとして反映」を押します。補正方式がFIRへ切り替わり、停止状態になります。「再生・調整」で音源を選び、通常の再生ボタンで試聴します。測定用MICは解放済みで、通常再生にはMIC権限を要求しません。
4. 「反映前に戻す」で、反映前のFIR・単純補正・音源・音量などを復元します。

保存済みの候補は同じ画面の「候補」から選べます。前回の候補1 `cae34bb91b6a53d07048aac6` と候補2 `7085475b8e7b33caac6c293e` も反映でき、再測定・再設計は不要です。外部ファイルにある候補は「実験用FIR」の「保存診断／候補JSONを読込」から読み込みます。両候補の係数ハッシュと比較履歴を保持します。

現在の共通出力ではDSPピーク上限0.9を超える場合、反映を止めて計算値と操作を表示します。「測定・試聴の共通出力」を下げ、同じ候補で再度「FIRとして反映」を押してください。再測定は不要です。候補に記録した測定時の音量は変えません。

## 品質と計算条件

試聴用の設計重みは`poc-listening-uniform-v1`です。要求帯域内を1、帯域外を0とし、品質合格マスク・SNR・反復間の振幅や位相の差から重みを決めません。正の重み20ビン以上という従来の条件も、試聴用には適用しません。元の品質評価・合格マスク・同期情報を残し、設計重みと区別して保存します。左右別の位相合わせや、自動での帯域縮小は行いません。

有限で正のクロック比と共通時刻モデルがあり、必要な伝達特性を取り出せれば、同期の信頼度や残差の品質基準超過だけでは中止しません。`OUTPUT_BOUND`などの過去の終了理由も、それだけでデータを拒否する理由には使いません。SNR不足の数、反復差、同期信頼度、残差は画面の注意情報です。

左右の測定欠損、応答配列の不正、非有限値、計算できない時刻対応、PCMクリップや実ルートの異常は止めます。補正側がすべてゼロの応答、収束不能、ゼロ係数・恒等フィルタも設計成功として扱いません。有限長FIRの正則化とブースト上限は維持しています。

測定時の音量でDSP出力余裕が足りなくても、有効な係数は候補として保持できます。保存候補の読込時は係数・ブースト・ハッシュを確認し、反映時と通常再生開始時に現在の共通出力でDSPピークを再評価します。上限超過を品質不足とまとめたり、音量を自動で下げたりしません。

反映後も実験用候補のままで、正式校正の成功記録は作りません。画面には反映した候補IDと「効果未検証」を表示します。再生出力や配置が測定時と異なる場合の効果も未確認です。

## 検証

| 項目 | 結果 |
|---|---|
| JVM | 100件成功。従来の正式判定・比較試験を含む |
| 追加したJVM回帰 | 全ビン低SNR・反復位相差・同期残差超過、非ゼロ応答10ビン、OUTPUT_BOUNDからの再設計、現音量での出力判定、不正配列・係数・取消を確認 |
| Android | 音声無効エミュレーターで9件成功。試聴の反映・復元、MIC権限なしの通常再生、既存履歴整理、UI操作、別プロセスでの復元を確認 |
| 保存済み候補 | 前回の候補1・2を読み込み、通常再生側へ渡す係数と一致。候補2の比較履歴は3件ともINSUFFICIENT_REDUCTIONのまま |
| Lint | エラー0件、既存の依存更新警告5件 |
| ビルド | debug APK・androidTest APKを生成 |

保存済み候補の試験には、前回の`compare3.json`と復元済み`compare6.json`を使いました。SHA-256はそれぞれ`e10a47a19fd0338482974749c7710b7e06bf2e2e07a241e9947939678590bddb`、`f0388123bc83a2e0658d253139b9652939498306730a4942b27db52250a76d03`です。エミュレーターへ転送後も一致を確認しました。

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
# Android試験は専用エミュレーターを -no-window -no-audio -no-snapshot で起動する。
# 実機で silentEmulator を指定して試験を実行しない。
$class='com.hebowiz.speakerarray.ExperimentalAppTest'
$runner='com.hebowiz.speakerarray.test/androidx.test.runner.AndroidJUnitRunner'
adb -s emulator-5556 shell pm revoke com.hebowiz.speakerarray android.permission.RECORD_AUDIO
adb -s emulator-5556 shell am instrument -w -e silentEmulator true -e class "$class#pocBadQualityOutputBoundCanApplyAtCurrentLevelAndUndo,$class#pocMeasurementCancelKeepsOldSettingsAndMainListeningControlsAreReachable" $runner
```

保存済み実測候補の試験は、上記2ファイルを検証用アプリの`files/poc-candidate1.json`と`files/poc-candidate2.json`へ配置し、`-e widebandFixtures true`を指定して`savedWidebandCandidatesApplyExactlyWithoutComparisonOrMic`を実行します。通常の試験では、この外部資料を使う1件はスキップします。再起動試験は`seedExperimentalForProcessRestart`、アプリのforce-stop、`restoreExperimentalInNewProcess`の順に実行します。

## 残る確認と成果物

今回は新しいAPKを実機へインストールしておらず、実機での新規測定・試聴も行っていません。測定からの直接設計には既存の取得処理を使います。取得後に行う品質判定は注意情報として残し、試聴用の設計を続けます。HFP実機で最後まで通ることと、耳での効果確認は次の検証です。

大規模JSON・エクスポート・履歴管理の再設計は後回しとしました。保存診断の読込上限だけは、前回の約2,210万文字の診断を扱えるよう試聴経路で3,200万文字にしました。直接測定の受け渡しにはファイル読込上限を使いません。前回観測した0バイト保存の問題は未修正で、保存後のファイル確認が引き続き必要です。

APKは`app/build/outputs/apk/debug/app-debug.apk`、テストAPKは`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`です。ハッシュと掲載先は[検証記録](validation.md)に記載します。実機上の既存設定・測定結果や、以前の成果物は削除していません。RFMonitorを読み取りのみで照合し、前後のHEAD・Git状態・256ファイルのSHA-256が一致しました。
