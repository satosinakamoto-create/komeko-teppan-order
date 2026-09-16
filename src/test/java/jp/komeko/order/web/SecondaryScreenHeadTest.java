package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「ボタンの先」の画面の見出しを、Figma の部品「見出し/ページ」にそろえる（2026-09-14）。
 *
 * <p><b>どこまで済んでいたか。</b>一覧側の 16 画面は 09-13 にそろえたが、
 * そこから押して開く画面（編集・詳細・マスタ）は {@code .section-title} のまま残っていた。
 * あちらは padding 0 の「行」で帯にならず、題も小さい。
 * 同じアプリの中で見出しが 2 種類ある状態だった。
 *
 * <p><b>そろえる形</b>（Figma 24:1392）
 * <pre>
 *   .page-head              padding 16/40・gap 16・中央ぞろえ
 *     .page-head__title     32px Bold
 *     .page-head__sub       14px #828282     … 件数・日付・親の名前
 *     .btn                  補足のすぐ右（左詰め。のばすで飛ばさない）
 *     .page-head__spacer    実行のボタンを右端へ送る
 * </pre>
 *
 * <p><b>「← 戻る」の位置は 2026-09-15 に右端から左端へ変えました（店主の判断）。</b>
 * 当初はここに「右端でよい（Figma の ト04b〜トt19b もそう描いてある）」と書いていましたが、
 * 戻るは「移動」・保存や追加は「実行」で種類が違うため、左＝戻る／右＝実行に分けました。
 * 経緯と寸法（設計 898:9098）は {@link #theBackButtonSitsAtTheLeftEdge()} と
 * {@link BackButtonOnTheLeftTest} にあります。逆側に置いて押し間違いの距離を取る、
 * という狙いそのものは変わっていません。
 *
 * <p>節の見出し（h2 の {@code .section-title}）はそのまま。
 * Figma でも別部品（見出し/節 24:1397）で、こちらは 24px の帯。
 */
@DisplayName("ボタンの先の画面も見出し帯にそろえる")
class SecondaryScreenHeadTest {

    private static final Path TPL = Path.of("src/main/resources/templates");

    /** テンプレート → その画面の題（Figma のモックに書いたもの）。 */
    private static final Map<String, String> SCREENS = new LinkedHashMap<>() {{
        put("inventory/ingredient-form.html", "食材を追加");          // ト04b
        put("inventory/ingredient-detail.html", null);                // ト04c（題は食材名）
        put("inventory/purchase-detail.html", null);                  // ト05e（題は店名）
        put("inventory/tax-rates.html", "税率・控除率マスタ");         // ト05f
        put("inventory/recipe-edit.html", null);                      // ト09c（題は商品名）
        put("admin/categories.html", "カテゴリを 編集・追加");          // ト11b
        put("admin/staff.html", "スタッフ 編集・追加");                 // ト15b
        put("admin/options.html", null);                              // ト10d（題は商品名）
        put("accountant/evidence-detail.html", null);                 // トt19b（題は店名）
    }};

    private String read(String name) throws Exception {
        return Files.readString(TPL.resolve(name)).replace("\r\n", "\n");
    }

    /** コメントを落とす。注意書きに一致して落ちる罠を避ける（他のテストと同じ）。 */
    private String withoutComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    /** 本文（{@code <main>} の中）の、いちばん上の見出しだけを切り出す。 */
    private String firstHead(String html) {
        String m = withoutComments(html);
        int at = m.indexOf("<main");
        assertThat(at).as("<main> が無い").isGreaterThanOrEqualTo(0);
        m = m.substring(at);
        int head = m.indexOf("class=\"page-head\"");
        assertThat(head).as("見出しの帯（.page-head）が無い").isGreaterThan(0);
        return m.substring(head, m.indexOf("</div>", head));
    }

    @Test
    @DisplayName("★ 9 画面とも h1 が見出し帯の中にある")
    void everySecondaryScreenHasTheBand() throws Exception {
        for (Map.Entry<String, String> e : SCREENS.entrySet()) {
            String band = firstHead(read(e.getKey()));
            assertThat(band)
                    .as(e.getKey() + " の題が page-head__title でない")
                    .contains("page-head__title");
            if (e.getValue() != null) {
                assertThat(band)
                        .as(e.getKey() + " の題の文言")
                        .contains(e.getValue());
            }
        }
    }

    @Test
    @DisplayName("★ h1 に section-title__text を残さない（帯と行が混在しない）")
    void noScreenKeepsTheOldRowHeading() throws Exception {
        for (String name : SCREENS.keySet()) {
            assertThat(withoutComments(read(name)))
                    .as(name + " に h1 の section-title__text が残っている")
                    .doesNotContain("<h1 class=\"section-title__text\"");
        }
    }

    /**
     * ★ 2026-09-15 に期待を<b>逆向きに</b>書き換えました（店主の判断）。
     *
     * <p>もとは「戻るは帯の右端（のばすで送る）」でした。設計もそうなっていました。
     * ところが戻るは「移動」、保存や追加は「実行」で種類が違い、
     * <b>分類をまとめて付ける</b>では {@code ← 食材・在庫へ戻る} と
     * {@code 選んだ分類を保存する} が右端で隣り合っていました。
     * 40 件ぶん選び終えた直後——いちばん押し間違えやすいところに、
     * 選択が全部消えるボタンが並んでいたことになります。
     *
     * <p>※ きっかけになったその画面自体は 2026-09-16 に無くなりました
     * （{@code IngredientCategoryRemovedTest}）。<b>判断は変えません。</b>
     * 同じ形——戻ると破壊的な実行が隣り合う——は他の画面でも起こりうるので、
     * 位置で分けておく意味は画面が 1 枚消えても変わらないためです。
     *
     * <p>左＝戻る／右＝実行に分けると、この事故は構造的に起きません。
     * Figma も 14 枚すべて同じ形に描き直してあります
     * （見出しの左右 padding は 0、戻るボタンと題の間は 40px。設計 898:9098）。
     *
     * <p>細かい寸法は {@link BackButtonOnTheLeftTest} が見ています。
     * ここでは「この 6 画面が左に置かれていること」だけを確かめます。
     */
    @Test
    @DisplayName("★ 「← 戻る」は帯の左端（題より前）")
    void theBackButtonSitsAtTheLeftEdge() throws Exception {
        String[] withBack = {
                "inventory/ingredient-form.html",
                "inventory/ingredient-detail.html",
                "inventory/purchase-detail.html",
                "inventory/tax-rates.html",
                "inventory/recipe-edit.html",
                "accountant/evidence-detail.html",
        };
        for (String name : withBack) {
            String band = firstHead(read(name));
            int back = band.indexOf("←");
            int title = band.indexOf("page-head__title");
            assertThat(back).as(name + " に 戻る が無い").isGreaterThan(0);
            assertThat(title).as(name + " に 題 が無い").isGreaterThan(0);
            assertThat(back).as(name + " の戻るが題より後ろにある").isLessThan(title);
        }
    }

    @Test
    @DisplayName("★ 補足（件数・日付・親の名前）は帯の中に残す")
    void theSubtitleSurvives() throws Exception {
        String[] withSub = {
                "admin/categories.html",
                "admin/staff.html",
                "admin/options.html",
                "accountant/evidence-detail.html",
        };
        for (String name : withSub) {
            assertThat(firstHead(read(name)))
                    .as(name + " の補足が消えている")
                    .contains("page-head__sub");
        }
    }
}
