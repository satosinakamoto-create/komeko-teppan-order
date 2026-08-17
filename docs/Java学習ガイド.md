# Java / Spring Boot 学習ガイド

このコードを **教材として読む順番** と、その場で押さえておきたい考え方をまとめました。
「動くものを読みながら覚える」ほうが、文法書を頭から読むより定着します。

前提: Java の `if` / `for` / クラスとメソッド、が何となく分かっていればOK。

---

## 読む順番（全 8 ステップ）

### STEP 1 — アプリの入口を見る

📄 `src/main/java/jp/komeko/order/KomekoOrderApplication.java`

たった数行ですが、ここで起きていることが Spring の根っこです。

- Java のプログラムは必ず `main` から始まる
- `@SpringBootApplication` を付けたクラスの**同じパッケージ配下**が自動で走査される
- 走査で見つかった `@Component` / `@Service` / `@Controller` / `@Repository` が
  **インスタンス化されて Spring が保持する**（この保持されたオブジェクトを **Bean** と呼ぶ）

> **DI（依存性注入）とは**
> 「必要なオブジェクトを自分で `new` せず、外から渡してもらう」やり方のこと。
> `ShopSettingService` は `ShopSettingRepository` を使いますが、自分で `new` していません。
> コンストラクタの引数に書いておくだけで Spring が渡してくれます。
> こうすると、テストのときに偽物を渡し替えられる＝テストしやすくなります。

**やってみる**: `KomekoOrderApplication` を実行 → <http://localhost:8080> が開くことを確認。

---

### STEP 2 — 一番シンプルなクラスで文法を確認する

📄 `domain/TaxCalculator.java` — 計算だけのクラス
📄 `domain/OrderStatus.java` — enum（列挙型）
📄 `domain/Allergen.java` — 値を持った enum

ここで覚えること:

| 文法 | このコードでの例 | ポイント |
|---|---|---|
| `static` メソッド | `TaxCalculator.includedTax()` | インスタンスを作らずに呼べる |
| `private` コンストラクタ | `TaxCalculator()` | 「インスタンス化させない」という意思表示 |
| `enum` | `OrderStatus.RECEIVED` | 決まった選択肢を型で表す。タイプミスがコンパイルで見つかる |
| enum にフィールドを持たせる | `OrderStatus("受付", …)` | ラベルや色を enum 自身に持たせられる |
| `switch` 式（Java 14〜） | `OrderStatus.allowedNext()` | `->` を使う新しい書き方。`break` が要らない |
| `List.of()` | 同上 | 変更できないリストを 1 行で作る |

**大事な設計の話**:
`allowedNext()` のように「許される遷移」を enum 自身に持たせると、
画面のボタンとサーバ側のチェックが**同じ 1 箇所**を見ることになります。
2 箇所に書くと、必ずいつかズレます。

**やってみる**: `src/test/java/.../OrderStatusTest.java` を読んで、テストを実行してみる。

---

### STEP 3 — DB とクラスの対応（JPA）

📄 `domain/Category.java` → いちばん単純なエンティティ
📄 `domain/MenuItem.java` → 関連あり
📄 `domain/Order.java` → このアプリの中心

覚えること:

```java
@Entity                    // このクラス = DB のテーブル 1 つ
@Table(name = "orders")    // テーブル名を変えたいとき（ORDER は SQL の予約語！）
@Id                        // 主キー
@GeneratedValue(...)       // 採番を DB に任せる
@Column(nullable = false)  // NOT NULL 制約
@ManyToOne                 // 多対一（商品 → カテゴリ）
@OneToMany(mappedBy = ...) // 一対多（注文 → 明細）
```

**引数なしコンストラクタが必要な理由**:
JPA は DB から読んだデータでオブジェクトを組み立てるとき、
まず空のインスタンスを作ってから値を入れます。だから `protected Category() {}` が要ります。

**LAZY と EAGER**:
`fetch = FetchType.LAZY` は「実際に使うまで DB から読まない」。
既定で全部読むと、1 件取るだけで関連テーブルまで芋づる式に読んでしまいます。
逆に LAZY のまま画面に渡すと `LazyInitializationException` が出ます（STEP 5 で解決します）。

**スナップショットという考え方**:
`OrderLine` は商品名と価格を**コピーして**持っています。
商品マスタを参照するだけだと、値上げしたときに過去の伝票の金額まで変わってしまいます。
会計に関わるところでは必ずコピーを取ります。これは Java の文法ではなく**設計の話**ですが、
実務ではこちらのほうがずっと重要です。

---

### STEP 4 — DB アクセス（Spring Data JPA）

📄 `repository/CategoryRepository.java` → 一番単純
📄 `repository/MenuItemRepository.java` → `@Query` と `@EntityGraph`
📄 `repository/DailyCounterRepository.java` → ロック

驚くところ: **インターフェースを書くだけで実装が自動生成されます。**

```java
public interface CategoryRepository extends JpaRepository<Category, Long> {
    // これだけで SELECT * FROM category WHERE visible = true ORDER BY sort_order が動く
    List<Category> findByVisibleTrueOrderBySortOrderAscIdAsc();
}
```

メソッド名が SQL に翻訳されます（**クエリメソッド**）。
複雑なものは `@Query` に JPQL（SQL に似た、テーブル名でなくクラス名を書く言語）を書きます。

**N+1 問題**:
商品 30 件を一覧表示するとき、カテゴリ名を出すたびに 1 回ずつ SELECT が飛ぶと
合計 31 回になります。これを N+1 問題と呼びます。
対策が `@EntityGraph`（一緒に読む）と `@BatchSize`（まとめて読む）です。
実務で最初に当たる性能問題なので、名前だけでも覚えておくと得します。

> **⚠️ 実際にこのプロジェクトでハマった話**
>
> `MenuItem.allergens` は `fetch = FetchType.EAGER`（常に一緒に読む）と書いてあるのに、
> 画面を描くところで `LazyInitializationException` が出ました。
>
> 原因は `@EntityGraph` です。これは既定で「**フェッチグラフ**」として扱われ、
> **`attributePaths` に書かなかった関連は、EAGER と宣言していても LAZY に上書きされます。**
>
> ```java
> // ✗ allergens が LAZY 扱いになり、画面で落ちる
> @EntityGraph(attributePaths = {"category"})
>
> // ○ 一緒に読むものを全部並べる
> @EntityGraph(attributePaths = {"category", "allergens"})
> ```
>
> 「エンティティ側の宣言」より「クエリ側の指定」が強い、と覚えておくとよいです。
> このバグはテスト（`CustomerFlowTest`）が見つけてくれました。
> **画面を 1 回描くテストがあるだけで、この手のミスは全部捕まえられます。**

---

### STEP 5 — 業務ロジック（Service とトランザクション）

📄 `service/ShopSettingService.java` → まず短いものから
📄 `service/OrderNumberService.java` → ロックとトランザクション
📄 `service/OrderService.java` → 本丸

**`@Transactional` とは**:
メソッドの最初から最後までを「ひとまとまり」として扱う指定です。
途中で例外が出たら、それまでの DB 変更が**すべて取り消され**ます。
注文だけ保存されて明細が保存されない、という中途半端な状態を防ぎます。

**`readOnly = true`**:
読むだけのメソッドに付けると速くなります。付ける癖をつけましょう。

**`open-in-view: false` と hydrate**:
このアプリは画面を描くときに DB 接続を持っていません（設定でそうしています）。
なので LAZY な関連は Service の中で読み終えておく必要があります。
それをやっているのが `OrderService.hydrate()` です。
`getOptions().size()` を呼ぶだけで実体化します。

**`Propagation.REQUIRES_NEW`**（`OrderNumberService`）:
「呼び出し元とは別のトランザクションで実行する」指定。
採番だけ切り離すことで、行ロックを握る時間を最短にしています。
少し難しい話なので、最初は「そういうものがある」で十分です。

---

### STEP 6 — 画面（Controller と Thymeleaf）

📄 `web/customer/MenuController.java` → 一番単純
📄 `web/customer/CartController.java` → POST とリダイレクト
📄 `templates/customer/menu.html` → 対応する画面

```java
@GetMapping("/items/{id}")            // GET /items/12 を受ける
public String item(@PathVariable Long id,   // URL の {id} が入る
                   Model model) {            // 画面に渡す入れ物
    model.addAttribute("item", ...);
    return "customer/item";                  // templates/customer/item.html を描画
}
```

**`@Controller` と `@RestController` の違い**:
`@Controller` の戻り値は「テンプレートの名前」。
`@RestController`（や `@ResponseBody`）は戻り値をそのまま JSON にします。

**PRG パターン（Post/Redirect/Get）**:
更新の POST のあと、HTML をそのまま返すと、
ブラウザを更新したときに同じ POST がもう一度送られて二重登録になります。
だから必ず `return "redirect:/cart";` のようにリダイレクトします。
画面に出したいメッセージは `RedirectAttributes`（フラッシュ属性）で渡します。

**Thymeleaf の読み方**:

| 書き方 | 意味 |
|---|---|
| `th:text="${item.name}"` | `getName()` の結果を要素の中身にする |
| `th:each="i : ${list}"` | ループ |
| `th:if` / `th:unless` | 条件表示 |
| `th:href="@{/items/{id}(id=${item.id})}"` | URL 組み立て（コンテキストパスも考慮される） |
| `th:replace="~{fragments/common :: flash}"` | 別ファイルの部品を差し込む |

> **落とし穴**: 同じタグに `th:if` と `th:replace` を書くと、
> `th:replace` が先に評価されて `th:if` が無視されます。条件は外側のタグに書きます。

---

### STEP 7 — 認証と認可（Spring Security）

📄 `config/SecurityConfig.java`
📄 `security/StaffUserDetailsService.java`
📄 `security/StaffUserDetails.java`

流れ:

```
ログインフォーム送信
   ↓
StaffUserDetailsService.loadUserByUsername("admin")   ← DB からユーザーを探す
   ↓
PasswordEncoder が入力パスワードと DB のハッシュを照合   ← ここは Security がやる
   ↓
成功 → セッションに認証情報を保存 → /kitchen へ
```

**パスワードは絶対に平文で保存しない**。BCrypt でハッシュ化します。
BCrypt は同じパスワードでも毎回違うハッシュになり、計算にわざと時間がかかります。

**CSRF**:
Spring Security は既定で CSRF 対策が有効です。
`th:action="@{...}"` を使えばトークンが自動で埋め込まれます。
**「POST したら 403 になる」ときは、まずここを疑ってください。** 初学者が必ず 1 回はハマります。

---

### STEP 8 — テストを書けるようになる

📄 `src/test/java/jp/komeko/order/domain/TaxCalculatorTest.java` → 素の JUnit
📄 `src/test/java/jp/komeko/order/service/OrderServiceIntegrationTest.java` → Spring 起動あり
📄 `src/test/java/jp/komeko/order/web/CustomerFlowTest.java` → 画面まで含めた確認

```java
@Test
@DisplayName("税込850円・8%なら内税は62円")
void 内税の計算() {
    assertThat(TaxCalculator.includedTax(850, 8)).isEqualTo(62);
}
```

- **単体テスト**（Spring を起動しない）は一瞬で終わる。計算や状態遷移はこれで十分。
- **結合テスト**（`@SpringBootTest`）は DB まで動かす。遅いので数を絞る。
- `MockMvc` を使うと、ブラウザ無しで画面のリクエストを試せます。
  **Thymeleaf の書き間違い（存在しないプロパティ）はこれで見つかります。**

実行:

```powershell
.\tools\run.ps1 -Test
```

---

## つまずいたときの調べ方

| 症状 | 見るところ |
|---|---|
| `LazyInitializationException` | Service の中で関連を読み終えているか（`hydrate`） |
| POST が 403 | フォームが `th:action="@{...}"` になっているか（CSRF） |
| `MultipleBagFetchException` | `List` を 2 段まとめて `JOIN FETCH` していないか |
| 画面が真っ白／500 | ログの `Exception evaluating SpringEL expression` を探す。Thymeleaf の式のミス |
| `NullPointerException` | Optional を `.get()` していないか。`orElseThrow()` を使う |
| テーブルが作られない | エンティティが `@SpringBootApplication` と同じパッケージ配下にあるか |
| データが消えた | `data\` フォルダを消していないか（H2 の実体） |

---

## 次に手を動かすなら（練習課題）

やさしい順。実際に手を入れると一気に身につきます。

1. **サンプルメニューを自分の店のものに書き換える** — `seed/DataSeeder.java`
2. **商品に「辛さレベル」バッジを足す** — `MenuItem` にフィールド追加 → 一覧テンプレートに表示
3. **待ち時間の計算式を変えてみる** — `OrderService.estimateWait()`。テストを書いてから変える
4. **注文にクーポン割引を足す** — `Order` に `discountAmount` を追加。
   税額の計算がどう変わるか考えるのが本番
5. ~~**「本日の残り在庫数」を管理する**~~ → **実装済みになりました（2026-08-17）**。
   答え合わせは `MenuItemRepository.tryDecrementStock`（条件付き UPDATE）と
   `OrderService.placeOrder` の減算ブロック、`StockManagementTest` を読むこと。
   ロックではなく「条件付き UPDATE 1 文」で解決している点が見どころです。
   代わりの練習課題：**「残数が 3 以下になったら厨房ボードに警告を出す」**を
   足してみてください（実装済みのコードを読んでから足すのも立派な練習です）

---

## おすすめの参考資料

- [Spring Boot 公式ガイド](https://spring.io/guides) — 短いチュートリアルが大量にある
- [Baeldung](https://www.baeldung.com/) — 「Spring ○○ how to」で検索すると大抵ある
- [Thymeleaf 公式ドキュメント](https://www.thymeleaf.org/documentation.html) — 式の書き方リファレンス
- 書籍『Spring 徹底入門』 — 日本語で体系的に読みたいとき
