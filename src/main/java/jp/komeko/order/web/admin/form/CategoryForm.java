package jp.komeko.order.web.admin.form;

import jakarta.validation.constraints.NotBlank;
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

    /**
     * 大分類の {@code <select>} で「＋ 新しい大分類を作る」を選んだときの合図（2026-09-20）。
     *
     * <p>★ これがそのまま {@code Category.setGroupName} に渡ると、
     * {@code @Size(max=20)} を通るので<b>例外も検証エラーも出ません</b>。
     * お客さまのメニューに {@code __new__} というタブが出るまで誰も気づけません。
     * コントローラの {@code create} で必ず実際の名前に直すこと。
     */
    public static final String NEW_GROUP = "__new__";

    /** 大カテゴリ（メニュー画面のタブ名）。空なら、このカテゴリ名がそのままタブになる。 */
    @Size(max = 20, message = "大カテゴリは20文字以内で入力してください")
    private String groupName;

    /**
     * 「＋ 新しい大分類を作る」を選んだときに打ち込む名前（2026-09-20）。
     *
     * <p>大分類は自由入力をやめました。{@code Category.getTabName()} を通って
     * お客さまのメニューのタブ名そのものになり、しかも
     * {@code MenuController.DRINK_SECTION} との完全一致で見た目が変わるためです。
     * 打ち間違えると、タブが 2 つに割れたり、飲み物用の並べ方が黙って効かなくなります。
     */
    @Size(max = 20, message = "大カテゴリは20文字以内で入力してください")
    private String newGroupName;

    // ★ 並び順（sortOrder）は 2026-09-19 に消しました。
    //   店主の指示「卓は編集する画面で並び替えは出来ない仕様にして」（カテゴリも同じ扱い）。
    //   並べ替えは一覧のドラッグ＆ドロップ（POST /admin/categories/place）だけが行い、
    //   新規追加は MenuService.nextCategorySortOrder() が自動で採番します。
    //
    //   ★ 戻すときは「フィールド・注釈・初期値・画面の入力欄」を必ず同時に動かすこと。
    //     初期値 0 のままフィールドだけ戻すと、画面に欄が無いぶん 0 が書かれ、
    //     更新したカテゴリが黙って一覧の先頭へ飛びます（@NotNull も @Min(0) も 0 は通す）。
    //     逆に初期値を外して @NotNull を残すと、毎回「並び順を入力してください」が出て
    //     どのカテゴリも二度と更新できなくなります。

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
        form.setGroupName(category.getGroupName());
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

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNewGroupName() {
        return newGroupName;
    }

    public void setNewGroupName(String newGroupName) {
        this.newGroupName = newGroupName;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
