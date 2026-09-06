package jp.komeko.order.web.admin.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import jp.komeko.order.domain.Allergen;
import jp.komeko.order.domain.MenuItem;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 商品の登録・更新フォーム（新規と編集で共用）。
 *
 * <p><b>エンティティを直接バインドしない理由</b>（{@link CategoryForm} と同じ）<br>
 * {@link MenuItem} をそのまま {@code @ModelAttribute} で受けると、
 * 画面に出していない項目まで送信されるだけで書き換えられてしまいます
 * （mass assignment）。商品には価格という「お金に直結する項目」があるので、
 * 受け取ってよい項目をこのクラスで明示的に限定します。
 *
 * <p><b>新規と編集の見分け方</b><br>
 * {@link #id} が null なら新規、値が入っていれば編集です。
 * テンプレート（item-form.html）はこの 1 項目だけで
 * 送信先 URL や見出しを切り替えています。
 *
 * <p><b>画像は {@link MultipartFile} で受け取る</b><br>
 * ファイルは通常の文字列パラメータとは送られ方が違うため
 * （フォームに {@code enctype="multipart/form-data"} が必要）、
 * 専用の型で受け取ります。中身の検証と保存は
 * {@code ImageStorageService} が担当します。
 */
public class MenuItemForm {

    /**
     * 「掲載する」ときだけ確かめる決まりの目印（設計 08-2 商品を追加）。
     *
     * <p><b>下書きは途中で保存できないと意味がありません。</b>
     * 商品名だけ思いついて開いた店主に、価格とカテゴリまで求めたら、
     * 保存できずに画面を閉じることになります。
     *
     * <p>そこで「入っているか」の決まりだけこのグループに入れ、
     * 掲載するときにだけ走らせます。
     * 長さや範囲の決まり（60 文字以内・0 円以上など）は<b>常に</b>走ります。
     * 下書きでも、入っている値が壊れていてよい理由はありません。
     */
    public interface Publish {
    }

    /** 新規なら null、編集なら対象商品の ID。 */
    private Long id;

    /**
     * ★ カテゴリと商品名だけは、下書きでも必ず要ります。
     *
     * <p>{@code menu_item.category_id} と {@code name} は DB で NOT NULL、
     * カテゴリは {@code @ManyToOne(optional = false)} です。
     * ここを空で保存できるようにするには両方を NULL 許可に変える必要があり、
     * カテゴリは商品一覧・お客さまのメニューの見出し・注文明細まで
     * 「必ずある」前提で書かれています。
     * 下書きのために全体の前提を緩めるのは割に合いません。
     *
     * <p>入力の負担としても、この 2 つは商品を思いついた時点で決まっています。
     * 途中で保存したくなるのは、価格や写真やアレルゲンを調べている最中です。
     */
    @NotNull(message = "カテゴリを選択してください")
    private Long categoryId;

    @NotBlank(message = "商品名を入力してください")
    @Size(max = 60, message = "商品名は60文字以内で入力してください")
    private String name;

    @Size(max = 300, message = "説明は300文字以内で入力してください")
    private String description;

    /** 税込価格（円）。日本円は小数が無いので整数で扱う。 */
    @NotNull(message = "価格を入力してください", groups = Publish.class)
    @Min(value = 0, message = "価格は0円以上で入力してください")
    @Max(value = 1000000, message = "価格は1,000,000円以下で入力してください")
    private Integer price;

    @NotNull(message = "調理時間を入力してください", groups = Publish.class)
    @Min(value = 0, message = "調理時間は0分以上で入力してください")
    @Max(value = 180, message = "調理時間は180分以下で入力してください")
    private Integer cookMinutes = 5;

    @NotNull(message = "並び順を入力してください", groups = Publish.class)
    @Min(value = 0, message = "並び順は0以上で入力してください")
    @Max(value = 9999, message = "並び順は9999以下で入力してください")
    private Integer sortOrder = 0;

    private boolean soldOut = false;

    /** 掲載するか。新規は「掲載する」を初期値にする。 */
    private boolean visible = true;

    /**
     * 書きかけ（編集中）か。設計 08-2 の「掲載」の 3 択のうち左端。
     *
     * <p>true の間は {@link jp.komeko.order.domain.MenuItem#isOrderable()} が
     * 必ず false を返すので、お客さまのメニューには並びません。
     */
    private boolean draft = false;

    /**
     * 掲載の 3 択（設計 08-2 の「掲載」）。{@code draft / published / hidden}。
     *
     * <p>画面のラジオはこれを指します。保存したときに何になるかは
     * <b>押したボタン</b>で決まります。
     * <ul>
     *   <li>下書きのまま保存 … 何を選んでいても 編集中</li>
     *   <li>掲載する         … 編集中ではなくなる。
     *       「掲載停止」を選んでいればそのまま止めた状態で保存する</li>
     * </ul>
     * ラジオとボタンの両方が同じ値を触るので、
     * <b>どちらが勝つかをここに書いておかないと</b>、
     * 実装のたびに解釈が変わります。ボタンが勝ちます。
     */
    private String publishState = "published";

    private boolean recommended = false;

    /**
     * チェックされたアレルゲン。
     *
     * <p>同じ name（allergens）のチェックボックスを複数並べると、
     * Spring がまとめて 1 つのコレクションに変換してくれます。
     * 文字列 "WHEAT" から {@link Allergen#WHEAT} への変換も Spring が自動で行います。
     *
     * <p>1 つもチェックされていないときはリクエストにパラメータ自体が現れないため、
     * この初期値（空）のまま残ります。毎回新しいインスタンスが作られるので、
     * 「全部のチェックを外す」も正しく空として扱われます。
     */
    private Set<Allergen> allergens = new LinkedHashSet<>();

    /** アップロードされた画像。未選択なら空（{@code isEmpty()} が true）。 */
    private MultipartFile image;

    /** 「現在の画像を削除する」にチェックが入ったか。 */
    private boolean removeImage = false;

    /**
     * すでに登録されている画像の公開パス。
     * 画面にサムネイルを出すためだけに使う表示専用の項目で、
     * ここに何を送られても保存処理では使いません（送信値は信用しない）。
     */
    private String currentImagePath;

    public MenuItemForm() {
    }

    /** 既存の商品から編集用フォームを組み立てる。 */
    public static MenuItemForm of(MenuItem item, Long categoryId) {
        MenuItemForm form = new MenuItemForm();
        form.setId(item.getId());
        form.setCategoryId(categoryId);
        form.setName(item.getName());
        form.setDescription(item.getDescription());
        form.setPrice(item.getPrice());
        form.setCookMinutes(item.getCookMinutes());
        form.setSortOrder(item.getSortOrder());
        form.setSoldOut(item.isSoldOut());
        form.setVisible(item.isVisible());
        form.setDraft(item.isDraft());
        form.setPublishState(item.isDraft() ? "draft" : (item.isVisible() ? "published" : "hidden"));
        form.setRecommended(item.isRecommended());
        // エンティティの Set をそのまま渡すと、フォーム側の操作が
        // エンティティに伝わってしまうのでコピーして持つ
        form.setAllergens(new LinkedHashSet<>(item.getAllergens()));
        form.setCurrentImagePath(item.getImagePath());
        return form;
    }

    /** 新規登録の入力かどうか。テンプレートの出し分けにも使う。 */
    public boolean isNew() {
        return id == null;
    }

    // ── getter / setter ──────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
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

    public Integer getPrice() {
        return price;
    }

    public void setPrice(Integer price) {
        this.price = price;
    }

    public Integer getCookMinutes() {
        return cookMinutes;
    }

    public void setCookMinutes(Integer cookMinutes) {
        this.cookMinutes = cookMinutes;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isSoldOut() {
        return soldOut;
    }

    public void setSoldOut(boolean soldOut) {
        this.soldOut = soldOut;
    }

    public String getPublishState() {
        return publishState;
    }

    public void setPublishState(String publishState) {
        this.publishState = publishState;
    }

    public boolean isDraft() {
        return draft;
    }

    public void setDraft(boolean draft) {
        this.draft = draft;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isRecommended() {
        return recommended;
    }

    public void setRecommended(boolean recommended) {
        this.recommended = recommended;
    }

    public Set<Allergen> getAllergens() {
        return allergens;
    }

    public void setAllergens(Set<Allergen> allergens) {
        this.allergens = (allergens == null) ? new LinkedHashSet<>() : allergens;
    }

    public MultipartFile getImage() {
        return image;
    }

    public void setImage(MultipartFile image) {
        this.image = image;
    }

    public boolean isRemoveImage() {
        return removeImage;
    }

    public void setRemoveImage(boolean removeImage) {
        this.removeImage = removeImage;
    }

    public String getCurrentImagePath() {
        return currentImagePath;
    }

    public void setCurrentImagePath(String currentImagePath) {
        this.currentImagePath = currentImagePath;
    }
}
