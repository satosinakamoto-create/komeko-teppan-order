package jp.komeko.order.web.admin.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jp.komeko.order.domain.StaffRole;

/**
 * スタッフ新規登録フォームの入力値を受け取るクラス（フォームオブジェクト）。
 *
 * <p><b>なぜエンティティ（{@link jp.komeko.order.domain.StaffUser}）を
 * そのままフォームに使わないのか</b><br>
 * 新規登録の画面で入力するのは「生のパスワード」ですが、
 * エンティティが持っているのは「ハッシュ化されたパスワード」です。
 * 型は同じ String でも意味がまったく違うので、同じクラスで兼用すると
 * 「うっかり生パスワードを DB に保存してしまう」事故が起きます。
 * 画面の都合（生パスワード・確認用の入力欄など）はフォームクラス側に置き、
 * エンティティは DB の都合だけを持つ、と役割を分けておくのが安全です。
 *
 * <p>もう一つの理由は<b>マスアサインメント対策</b>です。
 * エンティティを直接バインドすると、画面に出していない項目（例: enabled や role）を
 * リクエストに紛れ込ませるだけで書き換えられてしまいます。
 * フォームクラスに「受け取ってよい項目」だけを定義しておけば、それ以外は入る余地がありません。
 *
 * <p><b>Bean Validation のアノテーション</b>（{@code @NotBlank} など）は、
 * コントローラの引数に {@code @Valid} を付けたときに Spring が自動で検査してくれます。
 * 検査結果は直後の {@code BindingResult} 引数に入ります。
 * message 属性に日本語を書いておくと、そのまま画面に出せます。
 */
public class StaffForm {

    /**
     * ログイン ID。
     * 記号を許すと入力ミスや大文字小文字の取り違えが起きやすいので、
     * 半角英数字と一部の記号だけに絞っています。
     */
    @NotBlank(message = "ユーザー名を入力してください")
    @Size(min = 3, max = 40, message = "ユーザー名は3〜40文字で入力してください")
    @Pattern(regexp = "[A-Za-z0-9._-]*", message = "ユーザー名に使えるのは半角英数字と . _ - だけです")
    private String username;

    /**
     * 生のパスワード。ここで受け取った値は
     * {@code StaffUserService#create} の中で必ず BCrypt ハッシュに変換され、
     * 生のままでは 1 秒たりとも DB に入りません。
     *
     * <p>上限を 72 文字にしているのは BCrypt の仕様で、
     * 73 文字目以降が無視されてしまうためです（サービス側でも同じ検査をしています）。
     */
    @NotBlank(message = "パスワードを入力してください")
    @Size(min = 8, max = 72, message = "パスワードは8〜72文字で入力してください")
    private String password;

    /** 画面や記録に出る名前（「厨房 たろう」など）。 */
    @NotBlank(message = "表示名を入力してください")
    @Size(max = 40, message = "表示名は40文字以内で入力してください")
    private String displayName;

    /**
     * 権限。既定値を STAFF にしておくと、
     * 選び忘れたときに強い権限が付いてしまう事故を防げます（安全側に倒す）。
     */
    @NotNull(message = "権限を選んでください")
    private StaffRole role = StaffRole.STAFF;

    // ── getter / setter ──────────────────────────────────────────
    // Lombok は使わない方針なので手書きします。
    // Thymeleaf の th:field="*{username}" は、この getUsername() / setUsername() を
    // 呼び出して値の表示と受け取りを行っています。

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public StaffRole getRole() {
        return role;
    }

    public void setRole(StaffRole role) {
        this.role = role;
    }
}
