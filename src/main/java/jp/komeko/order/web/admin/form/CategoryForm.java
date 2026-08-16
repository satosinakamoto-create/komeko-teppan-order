package jp.komeko.order.web.admin.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import jp.komeko.order.domain.Category;

/**
 * カテゴリの登録・更新フォーム。
 *
 * <p><b>なぜエンティティ（{@link Category}）を直接フォームにバインドしないのか</b><br>
 * {@code @ModelAttribute Category category} と書くと、リクエストに含まれる名前と
 * 一致するフィールドが<b>すべて</b>書き換えられてしまいます。
 * たとえば画面には出していない {@code id} を攻撃者が
 * {@code id=1} のように付け足して送るだけで、別のレコードを狙って上書きできてしまいます。
 * これを <b>mass assignment（一括代入）脆弱性</b> と呼びます。
 *
 * <p>そこで「画面から受け取ってよい項目だけ」を持つ専用クラス（＝フォームクラス）を
 * 間に挟み、コントローラで必要な項目だけをエンティティへ写します。
 * 手間は増えますが、
 * <ul>
 *   <li>受け取ってよい項目が一覧で分かる</li>
 *   <li>画面都合の入力チェック（{@code @NotBlank} など）をエンティティに持ち込まなくて済む</li>
 *   <li>入力が数値でなかったときに一旦 null で受け取れる（エンティティの int では受けられない）</li>
 * </ul>
 * という利点があります。
 *
 * <p><b>数値を {@code int} ではなく {@link Integer} で持つ理由</b><br>
 * {@code int} は null を表現できないので、入力欄が空のときに
 * 「0 が入力された」のか「未入力」なのか区別できません。
 * ラッパー型の {@link Integer} なら未入力を null で受け取れて、
 * {@code @NotNull} で「入力してください」という日本語のメッセージを出せます。
 */
public class CategoryForm {

    /** 更新のときだけ入る。新規追加では null。 */
    private Long id;

    @NotBlank(message = "カテゴリ名を入力してください")
    @Size(max = 40, message = "カテゴリ名は40文字以内で入力してください")
    private String name;

    @NotNull(message = "並び順を入力してください")
    @Min(value = 0, message = "並び順は0以上で入力してください")
    @Max(value = 9999, message = "並び順は9999以下で入力してください")
    private Integer sortOrder = 0;

    /** お客さんのメニューに出すかどうか。新規追加では「出す」を初期値にする。 */
    private boolean visible = true;

    public CategoryForm() {
    }

    /**
     * 既存のカテゴリから編集用のフォームを組み立てる。
     *
     * <p>「エンティティ → フォーム」の変換をここに置いておくと、
     * コントローラが詰め替えのコードで長くならずに済みます。
     */
    public static CategoryForm of(Category category) {
        CategoryForm form = new CategoryForm();
        form.setId(category.getId());
        form.setName(category.getName());
        form.setSortOrder(category.getSortOrder());
        form.setVisible(category.isVisible());
        return form;
    }

    // ── getter / setter ──────────────────────────────────────────
    // Lombok は使わず手書きします。Spring はこの setter を使って
    // リクエストパラメータ（name=... など）の値を流し込みます。
    // setter が無いフィールドには値が入らない、という点も覚えておくと安全設計に役立ちます。

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
