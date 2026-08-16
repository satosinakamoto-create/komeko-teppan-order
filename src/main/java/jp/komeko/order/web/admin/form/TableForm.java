package jp.komeko.order.web.admin.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import jp.komeko.order.domain.DiningTable;

/**
 * 卓（テーブル）の登録・更新フォーム。
 *
 * <p><b>なぜエンティティ（{@link DiningTable}）を直接フォームにバインドしないのか</b><br>
 * コントローラの引数に {@code @ModelAttribute DiningTable table} と書いてしまうと、
 * Spring は「リクエストに含まれる名前と一致するフィールドを<b>すべて</b>」書き換えます。
 * つまり画面に出していない項目でも、名前さえ合っていれば外から送り込めてしまいます。
 * これを <b>mass assignment（一括代入）脆弱性</b> と呼びます。
 *
 * <p>この卓のクラスでは、それが特に危険です。{@link DiningTable} は
 * QR に埋め込む秘密の文字列 {@code accessToken} を持っているからです。
 * もしエンティティを直接バインドしていたら、
 * <pre>
 *   POST /admin/tables/3   name=3番テーブル&amp;accessToken=aaaa
 * </pre>
 * のようなリクエストだけで、<b>攻撃者が自分の知っているトークンを卓に設定できて</b>しまい、
 * 店に来ていない人が任意の卓の伝票へ注文を入れられるようになります。
 * （実際には {@code accessToken} に setter が無いので現状は通りませんが、
 * 「setter が無いから大丈夫」という守り方は、あとで誰かが setter を足した瞬間に崩れます）
 *
 * <p>そこで<b>画面から受け取ってよい項目だけ</b>を持つ専用クラス（＝フォームクラス）を
 * 間に挟み、コントローラで必要な項目だけをエンティティへ写します。
 * このクラスに {@code accessToken} が無いこと自体が「トークンは画面から変えられない」
 * という設計の宣言になっています。QR の再発行だけは
 * 専用の URL（{@code /admin/tables/{id}/regenerate}）で行います。
 *
 * <p><b>数値を {@code int} ではなく {@link Integer} で持つ理由</b><br>
 * {@code int} は null を表現できないので、入力欄が空のときに
 * 「0 と入力された」のか「未入力」なのか区別できません。
 * ラッパー型の {@link Integer} なら未入力を null で受け取れるので、
 * {@code @NotNull} で「入力してください」という日本語のメッセージを出せます。
 */
public class TableForm {

    /** 更新のときだけ入る。新規追加では null。 */
    private Long id;

    /**
     * 卓の呼び名（例: 1番テーブル / カウンター2）。
     * 厨房のボードやホールの一覧にそのまま出るので、長さはエンティティ側と同じ 20 文字に揃える。
     */
    @NotBlank(message = "卓名を入力してください")
    @Size(max = 20, message = "卓名は20文字以内で入力してください")
    private String name;

    /** 席数。伝票を開くときの人数の目安に使う。 */
    @NotNull(message = "席数を入力してください")
    @Min(value = 1, message = "席数は1以上で入力してください")
    @Max(value = 99, message = "席数は99以下で入力してください")
    private Integer capacity = 4;

    /** 一覧に並べる順番。小さいほど先。 */
    @NotNull(message = "並び順を入力してください")
    @Min(value = 0, message = "並び順は0以上で入力してください")
    @Max(value = 9999, message = "並び順は9999以下で入力してください")
    private Integer sortOrder = 0;

    /**
     * 稼働中かどうか。false にすると QR を読んでも注文できない。
     * 新規追加では「使う」を初期値にする。
     */
    private boolean active = true;

    public TableForm() {
    }

    /**
     * 既存の卓から編集用のフォームを組み立てる。
     *
     * <p>「エンティティ → フォーム」の変換をここに置いておくと、
     * コントローラが詰め替えのコードで長くならずに済みます。
     * ここでも {@code accessToken} は写していません（写す必要が無いからです）。
     */
    public static TableForm of(DiningTable table) {
        TableForm form = new TableForm();
        form.setId(table.getId());
        form.setName(table.getName());
        form.setCapacity(table.getCapacity());
        form.setSortOrder(table.getSortOrder());
        form.setActive(table.isActive());
        return form;
    }

    // ── getter / setter ──────────────────────────────────────────
    // Lombok は使わず手書きします。Spring はこの setter を使って
    // リクエストパラメータ（name=... など）の値を流し込みます。
    // 「setter が無いフィールドには値が入らない」という点も、
    // 上の mass assignment の話とあわせて覚えておくと安全設計に役立ちます。

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

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
