package jp.komeko.order.web.kitchen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 品切れ・残数で、押しても画面が動かないようにする（2026-09-14、店主「scroll するから修正」）。
 *
 * <p><b>仕掛けは前からあった</b>。送信の直前に {@code window.scrollY} を覚えておき、
 * 戻ってきたら同じ位置へ戻す、というもの。それでも動いていた。
 *
 * <p><b>理由は「ピクセルで覚えていた」こと。</b>
 * 押すと戻り先でフラッシュ通知（「◯◯を品切れにしました」）が本文の先頭に増える。
 * <b>上に要素が増えたぶん、中身は下へずれる</b>。
 * 同じピクセル位置へ戻しても、押した行は通知の高さぶん下に来る——
 * 画面が動いたように見えるのはこれ。
 *
 * <p><b>直し方：行を基準にする。</b>
 * 送信の直前に「押した行の id」と「その行が画面のどの高さにあったか」を覚え、
 * 戻ってきたらその行が同じ高さに来るよう合わせる。
 * 上に何が増えても、押した行は<b>指の下から動かない</b>。
 *
 * <p>★ {@code behavior:'instant'} を外さないこと。
 * app.css が html に {@code scroll-behavior: smooth} を掛けているので、
 * 既定のままだと「その場にとどまる」ための処理が、かえって画面を滑らせる。
 *
 * <p>★ 通信が速いと {@code load} より前にブラウザのアンカー移動が終わっている。
 * 逆に遅いと後になる。どちらでも効くよう、読み込み完了のあとに 1 回上書きする形は変えない。
 */
@DisplayName("品切れ・残数は押しても画面が動かない")
class StockScrollAnchorTest {

    private static final Path TPL =
            Path.of("src/main/resources/templates/kitchen/stock.html");

    private String tpl() throws Exception {
        return Files.readString(TPL).replace("\r\n", "\n");
    }

    /** コメントを落とす。注意書きに一致して落ちる罠を避ける。 */
    private String withoutComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    @DisplayName("★ 覚えるのは「行の id」と「その行の画面内の高さ」")
    void itRemembersTheRowNotThePixelOffset() throws Exception {
        String s = withoutComments(tpl());
        assertThat(s).contains("closest('tr')");
        assertThat(s).contains("getBoundingClientRect().top");
        // ページ全体のスクロール量だけを覚える形に戻さない
        assertThat(s).doesNotContain("String(window.scrollY)");
    }

    @Test
    @DisplayName("★ 戻すのは「その行が同じ高さに来る位置」")
    void itRestoresRelativeToTheRow() throws Exception {
        String s = withoutComments(tpl());
        assertThat(s).contains("document.getElementById(");
        assertThat(s).contains("behavior: 'instant'");
    }

    @Test
    @DisplayName("★ 復元したら覚えたものは消す（次に開いたとき途中から始まらない）")
    void theMemoryIsClearedAfterUse() throws Exception {
        String s = withoutComments(tpl());
        assertThat(s).contains("sessionStorage.removeItem");
        // localStorage だと次に開いたときまで残る
        assertThat(s).doesNotContain("localStorage");
    }

    @Test
    @DisplayName("★ 行には id が付いている（戻る先の目印）")
    void everyRowHasAnId() throws Exception {
        assertThat(tpl()).contains("th:id=\"'item-' + ${item.id}\"");
    }
}
