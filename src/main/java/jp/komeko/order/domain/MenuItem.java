package jp.komeko.order.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 商品（メニュー 1 品）。
 *
 * <p><b>金額を int で持っている理由</b><br>
 * 日本円には小数がないため、円単位の整数で扱うのが最も安全です。
 * {@code double} は 0.1 を正確に表現できず金額計算でズレるので絶対に使いません。
 * （ドルやユーロのように小数がある通貨を扱うときは {@code BigDecimal} を使います）
 *
 * <p><b>価格は税込</b><br>
 * 日本では総額表示が義務なので、{@link #price} には最初から税込価格を入れます。
 * 内税額は「税込価格 × 8 ÷ 108」で逆算します（{@code TaxCalculator} 参照）。
 */
@Entity
@Table(name = "menu_item")
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属カテゴリ。
     * {@code @ManyToOne} は「多対一」。商品はたくさん、カテゴリは 1 つ。
     * {@code fetch = LAZY} は「必要になるまで DB から読まない」設定で、
     * 無駄な SQL を減らすため基本 LAZY にしておくのが定石です。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @NotBlank(message = "商品名を入力してください")
    @Size(max = 60, message = "商品名は60文字以内で入力してください")
    @Column(nullable = false, length = 60)
    private String name;

    @Size(max = 300, message = "説明は300文字以内で入力してください")
    @Column(length = 300)
    private String description;

    /** 税込価格（円）。 */
    @Min(value = 0, message = "価格は0円以上で入力してください")
    @Column(nullable = false)
    private int price;

    /** 商品画像の公開パス（例: /uploads/abc123.jpg）。未設定なら null。 */
    @Column(length = 200)
    private String imagePath;

    /** 品切れフラグ。true の間はお客さんの画面でグレーアウトし、注文できない。 */
    @Column(nullable = false)
    private boolean soldOut = false;

    /**
     * 残数（在庫）。
     *
     * <p><b>null は「在庫を管理しない」という意味</b>です。
     * ドリンクのように実質無限の品まで数を数えるのは現場の負担にしかならないので、
     * 「数量限定の品だけ数字を入れる」というオプトイン方式にしています。
     *
     * <p>だからこそ型は {@code int} ではなく {@link Integer}（ラッパー型）。
     * プリミティブの {@code int} は null を表現できないため、
     * 「0 個」と「管理していない」を区別できなくなってしまいます。
     *
     * <p>注文が入ると減り、キャンセルで戻ります。増減は必ず
     * {@code MenuItemRepository#tryDecrementStock}（条件付き UPDATE）経由で行い、
     * Java 側で「読んで、引いて、書き戻す」は<b>絶対にしない</b>こと。
     * その書き方は 2 人が同時に注文したとき最後の 1 個を 2 人に売ってしまいます。
     */
    @Column(name = "stock_remaining")
    private Integer stockRemaining;

    /** 掲載フラグ。false は「季節外れなので今は出さない」など。 */
    @Column(nullable = false)
    private boolean visible = true;

    /**
     * 書きかけ（編集中）かどうか。設計 08-2 商品を追加（315:1983）。
     *
     * <p><b>なぜ visible の false と別に持つのか</b><br>
     * 「掲載停止」と「編集中」は、同じ<b>出していない</b>でも意味がまるで違います。
     * <ul>
     *   <li>掲載停止 … 一度出したものを引っ込めた。<b>中身はそろっている</b></li>
     *   <li>編集中   … まだ作りかけ。価格が空かもしれない</li>
     * </ul>
     * 真偽値 1 つで両方を表すと、商品一覧で「作りかけ」と
     * 「季節外れでいま隠しているもの」が同じ棚に並びます。
     * 店主が探しているのはたいてい前者なので、区別できないと毎回全部見ることになります。
     *
     * <p><b>{@code columnDefinition} に DEFAULT を書く理由</b><br>
     * dev / demo は {@code ddl-auto: update} で列を足します。
     * NOT NULL の列を DEFAULT 無しで足すと、既存の行を埋められず失敗します。
     * 本番（Flyway）側にも同じ既定値を書いてあります（V12）。
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean draft = false;

    /** おすすめバッジを付けるか。 */
    @Column(nullable = false)
    private boolean recommended = false;

    @Column(nullable = false)
    private int sortOrder = 0;

    /**
     * 標準の調理時間（分）。待ち時間の目安計算に使う。
     * 鉄板でじっくり焼く商品は長め、ドリンクは 0〜1 分。
     */
    @Min(value = 0, message = "調理時間は0以上で入力してください")
    @Column(nullable = false)
    private int cookMinutes = 5;

    /**
     * 含まれるアレルゲン。
     *
     * <p>{@code @ElementCollection} は「エンティティではない値の集合」を
     * 別テーブル（menu_item_allergen）に保存する仕組みです。
     * 件数が少なく必ず一緒に表示するので EAGER（常に一緒に読む）にしています。
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "menu_item_allergen", joinColumns = @JoinColumn(name = "menu_item_id"))
    @Column(name = "allergen", length = 30)
    @Enumerated(EnumType.STRING)
    private Set<Allergen> allergens = EnumSet.noneOf(Allergen.class);

    /**
     * オプショングループ（トッピング・サイズなど）。
     * {@code cascade = ALL} + {@code orphanRemoval = true} で、
     * 商品を消したらオプションも一緒に消える／リストから外したら DB からも消える、
     * という「商品に完全に従属する」関係を表しています。
     */
    @OneToMany(mappedBy = "menuItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    @BatchSize(size = 50)
    private List<OptionGroup> optionGroups = new ArrayList<>();

    protected MenuItem() {
    }

    public MenuItem(Category category, String name, int price) {
        this.category = category;
        this.name = name;
        this.price = price;
    }

    // ── ビジネスロジック ──────────────────────────────────────────

    /**
     * 小麦を含まない＝グルテンフリーとして訴求できるか。
     * 米粉のお店の一番の売りなので、画面でバッジを出すのに使います。
     */
    public boolean isGlutenFree() {
        return !allergens.contains(Allergen.WHEAT);
    }

    /** 残数を管理している商品か。 */
    public boolean isStockTracked() {
        return stockRemaining != null;
    }

    /** 残数管理していて、かつ売り切れた（0 以下になった）か。 */
    public boolean isOutOfStock() {
        return stockRemaining != null && stockRemaining <= 0;
    }

    /**
     * いま注文できる状態か。
     * 「掲載中」かつ「手動の品切れフラグが立っていない」かつ「残数が尽きていない」。
     * 品切れの理由が手動フラグでも残数ゼロでも、お客さまから見れば同じ「売り切れ」です。
     *
     * <p><b>★ 書きかけ（{@link #isDraft()}）はここで必ず落とすこと。</b><br>
     * 画面側で {@code draft} を見て隠していても、
     * 判定がこの 1 か所に無いと、あとから足した画面や API が素通りさせます。
     * 作りかけの商品がお客さまのメニューに並ぶのは、
     * 価格が空のまま注文されるということでもあります。
     */
    public boolean isOrderable() {
        return !draft && visible && !soldOut && !isOutOfStock();
    }

    /**
     * 掲載できるだけの中身がそろっているか（設計 08-2 の「掲載する」が押せる条件）。
     *
     * <p>商品名・カテゴリ・価格の 3 つ。写真は無くても掲載できます
     * （設計の但し書き「無くても掲載できます」）。
     *
     * <p>価格は 0 も通します。0 は「時価」の意味で、
     * 金額はスタッフが店舗端末で決めるためです
     * （そのぶん品切れを外せないようにしてある。{@code MenuService#toggleSoldOut}）。
     */
    public boolean isReadyToPublish() {
        return name != null && !name.isBlank()
                && category != null
                && price >= 0;
    }

    /** 双方向関連を安全に張るためのヘルパー。 */
    public void addOptionGroup(OptionGroup group) {
        optionGroups.add(group);
        group.setMenuItem(this);
    }

    public void removeOptionGroup(OptionGroup group) {
        optionGroups.remove(group);
        group.setMenuItem(null);
    }

    // ── getter / setter ──────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getPrice() {
        return price;
    }

    public void setPrice(int price) {
        this.price = price;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public boolean isSoldOut() {
        return soldOut;
    }

    public void setSoldOut(boolean soldOut) {
        this.soldOut = soldOut;
    }

    public Integer getStockRemaining() {
        return stockRemaining;
    }

    public void setStockRemaining(Integer stockRemaining) {
        this.stockRemaining = stockRemaining;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isDraft() {
        return draft;
    }

    public void setDraft(boolean draft) {
        this.draft = draft;
    }

    public boolean isRecommended() {
        return recommended;
    }

    public void setRecommended(boolean recommended) {
        this.recommended = recommended;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public int getCookMinutes() {
        return cookMinutes;
    }

    public void setCookMinutes(int cookMinutes) {
        this.cookMinutes = cookMinutes;
    }

    public Set<Allergen> getAllergens() {
        return allergens;
    }

    public void setAllergens(Set<Allergen> allergens) {
        this.allergens = (allergens == null) ? EnumSet.noneOf(Allergen.class) : EnumSet.copyOf(allergens);
    }

    public List<OptionGroup> getOptionGroups() {
        return optionGroups;
    }
}
