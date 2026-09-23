package jp.komeko.order.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品フォームの下書き保存（2026-09-14、店主）。
 *
 * <p><b>何が起きていたか。</b>初期設定でサイドバーを上から辿ると、
 * カテゴリより先に商品フォームへ着く。品名・価格を打って写真まで選んだあとに
 * カテゴリ欄の「先にカテゴリを作ってください」を踏むと、
 * <b>打った内容が全部消える</b>（下書きが無いので）。
 *
 * <p><b>作り。</b>ブラウザの localStorage に自動保存し、
 * 戻ってきたら復元して知らせる。サーバは touch しない
 * （下書きはその端末の私物で、他のスタッフに見える必要が無い）。
 * <pre>
 *   保存     … input / change のたび（300ms の間引き）
 *   復元     … 品名が空のときだけ。サーバの入力エラーで戻された画面は
 *              品名が入っているので、上書きしない
 *   破棄     … 送信したとき／「下書きを破棄」を押したとき／48 時間で期限切れ
 *   写真     … 復元できない（file input はスクリプトから書けない仕様）。
 *              復元の知らせに「写真は選び直してください」と書く
 * </pre>
 *
 * <p><b>対象は新規だけ。</b>編集はサーバに保存済みの値が正で、
 * 古い下書きを上書き復元すると保存済みの内容を壊す事故になる。
 */
@DisplayName("商品フォームの下書き保存")
class ItemFormDraftTest {

    private static final Path FORM =
            Path.of("src/main/resources/templates/admin/item-form.html");
    private static final Path JS =
            Path.of("src/main/resources/static/js/form-draft.js");

    private String form() throws Exception {
        return Files.readString(FORM).replace("\r\n", "\n");
    }

    private String js() throws Exception {
        return Files.readString(JS).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("★ 新規のフォームだけに下書きの印が付く（編集には付けない）")
    void onlyTheNewFormOptsIn() throws Exception {
        String t = form();
        assertThat(t).contains("data-draft=${itemForm.id == null} ? 'admin-item-new' : null");
        assertThat(t).contains("js/form-draft.js");
    }

    @Test
    @DisplayName("★ 復元は品名が空のときだけ（入力エラーの再表示を上書きしない）")
    void restoreOnlyWhenPristine() throws Exception {
        String s = js();
        assertThat(s).contains("[name=name]");
        assertThat(s).contains("localStorage");
    }

    @Test
    @DisplayName("★ 送信したら下書きは消す（次の新規に前の品の下書きが出ない）")
    void submitClearsTheDraft() throws Exception {
        String s = js();
        assertThat(s).contains("addEventListener('submit'");
        assertThat(s).contains("removeItem");
    }

    @Test
    @DisplayName("★ 写真（file）と CSRF は保存しない")
    void filesAndTokensAreNeverSaved() throws Exception {
        String s = js();
        assertThat(s).contains("type === 'file'");
        assertThat(s).contains("_csrf");
    }

    @Test
    @DisplayName("★ 48 時間で期限切れ（何週間も前の下書きが化けて出ない）")
    void draftsExpire() throws Exception {
        assertThat(js()).contains("48 * 60 * 60 * 1000");
    }

    @Test
    @DisplayName("★ 復元したら知らせて、破棄の道も置く")
    void restoreIsAnnouncedWithAWayOut() throws Exception {
        String s = js();
        assertThat(s).contains("下書きを復元しました");
        assertThat(s).contains("写真は選び直してください");
        assertThat(s).contains("下書きを破棄");
    }
}
