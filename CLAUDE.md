# CLAUDE.md — 米粉と鉄板 モバイルオーダー

このプロジェクトの恒久的な約束事。コードを書く前に必ず目を通すこと。

## プロジェクト概要

米粉と鉄板のテイクアウト店向けモバイルオーダー。
QR → スマホ注文 → 番号呼び出し → 店頭会計。**オンライン決済は扱わない。**

- 言語: Java 21 / Spring Boot 3.3.5 / Thymeleaf / Spring Data JPA / Spring Security
- ビルド: Maven（`.\tools\run.ps1` で起動。Maven 未インストールでも動く）
- DB: dev = H2 ファイル（`data\komeko.mv.db`）、prod = PostgreSQL
- パッケージ: `jp.komeko.order`

## コマンド

```powershell
.\tools\run.ps1                 # 開発起動（http://localhost:8080）
.\tools\run.ps1 -Test           # テスト
.\tools\run.ps1 -Package        # 実行可能 jar
.\tools\run.ps1 -Port 8081      # ポート変更
```

DB をリセットしたいときは `data\` フォルダを削除して再起動する。

## コーディング規約

### 全般

- **Lombok は使わない。** getter / setter は手書きする（初学者が読める状態を保つため）。
- コメントと画面文言はすべて日本語。
- 学習用プロジェクトなので、**「なぜそう書くか」のコメントを厚めに**書く。
  ただし処理の逐語訳（`// i を 1 増やす`）は書かない。
- クラス名・メソッド名は英語、変数名も英語。日本語識別子は使わない。

### 金額

- **金額は必ず `int`（円）**。`double` / `float` は禁止（誤差が出る）。
- 価格は**税込**で保持する（日本の総額表示義務に合わせる）。
- 税額は `TaxCalculator.includedTax(税込金額, 税率)` で逆算する。自前で計算しない。
- テイクアウトは軽減税率 8%。税率は `ShopSetting.taxRatePercent` から取り、ハードコードしない。
- 注文には**注文時点の税率と価格をスナップショット**として保存する。マスタを参照しない。

### JPA / DB

- `open-in-view: false`。**画面描画時には DB 接続が無い。**
  必要な関連は Service の `@Transactional` の中で読み終えてから返すこと。
  遅延読み込みの実体化は `getXxx().size()` を呼ぶ（`hydrate` という名前のメソッドにまとめる）。
- 関連は原則 `FetchType.LAZY`。まとめ読みが必要なら `@EntityGraph` か `@BatchSize`。
- `List` を 2 段まとめて `JOIN FETCH` すると `MultipleBagFetchException` になる。
  1 段だけ fetch し、もう 1 段は `@BatchSize` で解決する。
- テーブル名 `orders`（`ORDER` は SQL の予約語）。
- 本番は `ddl-auto: validate`。スキーマ変更を入れるときは Flyway 導入を検討する（未導入）。

### Web 層

- **PRG パターン厳守。** 更新系の POST は必ず `redirect:` で返す。
- フォームは必ず `th:action="@{/...}"`。素の `action=` は CSRF で 403 になる。
- HTML の `<form>` は入れ子にできない。
- 画面に出すメッセージは `RedirectAttributes` のフラッシュ属性で渡す。
  キーは `flashSuccess` / `flashInfo` / `flashErrors`（`List<String>`）に統一。
- エンティティを直接フォームにバインドしない（Form クラスを噛ませる）。
  例外は `ShopSetting`（`ShopSettingService.save` が必要な項目だけ写している）。
- 業務ロジックはコントローラに書かず Service に置く。

### Thymeleaf

- レイアウトは 3 種類。ページ側は必ずこの形で書く。

  ```html
  <html th:replace="~{layout/customer :: layout('タイトル', ~{::main})}">
  <body><main> … </main></body></html>
  ```

  | レイアウト | 用途 |
  |---|---|
  | `layout/customer` | お客さん向け |
  | `layout/staff` | 厨房・管理 |
  | `layout/plain` | ログイン・エラー・印刷 |

- **同じタグに `th:if` と `th:replace` を書かない。** `th:replace` が先に評価され `th:if` が無視される。
  条件は外側のタグに書く。
- プロパティ記法は getter に対応する（`isXxx()` / `getXxx()` → `${obj.xxx}`）。
  record のアクセサは `${obj.xxx()}` と直接呼ぶ。
- 金額表示は `${#numbers.formatInteger(v, 1, 'COMMA')}`。

### CSS

- **`src/main/resources/static/css/app.css` が唯一の正。** 新しいクラスを勝手に増やさない。
  既存クラスの組み合わせで足りないときだけ追加し、追加したらこの規約の意図（下記）に沿わせる。
- デザインコンセプトは「鉄板の熱と、米粉の白」。
  生成り（面）／鉄黒（線・夜）／きつね色（アクセント）の 3 色構成。
  **アクセント色は「押せるもの」「熱いもの」だけに使う。**
- 見出しは明朝（`--font-serif`）、本文はゴシック（`--font-sans`）。
- 外部フォント・外部 CDN は読み込まない（店内 Wi-Fi にネットが無くても崩れないようにする）。
- タップ領域は 48px 以上（`--tap`）。入力欄の `font-size` は 16px 未満にしない（iOS が勝手に拡大する）。
- ダークモードは `prefers-color-scheme` で自動追従。新しい色はセマンティック変数経由で使う。

### セキュリティ

- 認証・認可は Spring Security に任せる。自作しない。
- パスワードは BCrypt。平文で保存・ログ出力しない。
- お客さんの注文は連番 ID ではなく `publicToken`（UUID）で参照する。
- 価格・品切れ・選択肢の妥当性は**必ずサーバ側で再検証**する。
  セッションのカートの値をそのまま信じて会計しない（`CartService.refresh`）。
- アップロードファイル名は必ず自前で作り直す。元のファイル名を使わない。
- 認可ルールを増やすときは `SecurityConfig` の URL とコントローラのマッピングを必ず突き合わせる。

### テスト

- 単体テストは Spring を起動しない素の JUnit で書く（速さが正義）。
- DB が要るものだけ `@SpringBootTest` + `@ActiveProfiles("test")`。
- `@DisplayName` は日本語。**そのテストが何を守っているかをコメントで書く。**
- 金額計算・状態遷移・カートのマージは必ずテストを書く（壊れると実害が出るため）。

## ドキュメントの置き場所

- 仕様・設計判断 → `docs\仕様.md`
- 現在地・次にやること → `docs\引き継ぎ.md`
- Java / Spring の学習メモ → `docs\Java学習ガイド.md`
- 恒久的な約束事 → この `CLAUDE.md`

## やらないと決めたこと

意図的に入れていない。増やす前にここを読むこと。

- オンライン決済（会計は店頭）
- イートイン（卓番 QR）。将来足せるよう `Order.taxRatePercent` は注文ごとに持たせてある
- 会員登録・ポイント
- 複数店舗対応
- 複数サーバでの冗長構成（SSE がインメモリのため 1 台前提）
