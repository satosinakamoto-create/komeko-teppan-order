package jp.komeko.order.inventory.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 貼り付けたテキストからレシピを読む（設計 ト09d 842:10569）。
 *
 * <p>Figma のカードにこう書いてあります——
 * 「スマホのメモや LINE に書いたレシピを、そのまま貼るだけ」
 * 「1 行に『材料 分量』で書いてあれば読めます」。
 *
 * <p><b>AI を使いません。</b>だから API キーが無くても動き、課金もゼロです。
 * 読めなかった行は<b>捨てずに材料名だけの行として残し</b>、確認画面で人が直します。
 * この画面の約束は「読んだまま保存しない」なので、
 * 読み取りが甘くても<b>人の目を通る限り害になりません</b>。
 *
 * <p><b>商品名と材料の見分け方</b>（この実装の決め）
 * <pre>
 *   分量（数字）で終わる行 … 材料
 *   それ以外の行           … 新しい商品の名前
 *   空行                   … 区切り（次の行は商品名として読む）
 * </pre>
 * ノートの書き方として自然な形です。外しても確認画面で直せます。
 *
 * <p>Spring を起動しない素の JUnit で書きます（速さが正義）。
 */
@DisplayName("貼り付けたテキストのレシピ読み取り")
class RecipeTextParserTest {

    private final RecipeTextParser parser = new RecipeTextParser();

    // ---------------------------------------------------------------- 基本

    @Test
    @DisplayName("★ 商品名の下に「材料 分量」が並ぶ形を読む")
    void readsADishFollowedByItsIngredients() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                肉玉米粉そば
                キャベツ 150g
                豚バラ 40g
                米粉 120g
                """);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).name()).isEqualTo("肉玉米粉そば");
        assertThat(items.get(0).lines()).hasSize(3);
        assertThat(items.get(0).lines().get(0).name()).isEqualTo("キャベツ");
        assertThat(items.get(0).lines().get(0).quantity()).isEqualByComparingTo("150");
    }

    @Test
    @DisplayName("★ 空行で区切って 2 品以上読める")
    void readsSeveralDishes() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                肉玉米粉そば
                キャベツ 150g

                豚平焼き
                卵 2個
                大葉 3枚
                """);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).name()).isEqualTo("肉玉米粉そば");
        assertThat(items.get(1).name()).isEqualTo("豚平焼き");
        assertThat(items.get(1).lines()).hasSize(2);
    }

    // ---------------------------------------------------------------- 書き方のゆれ

    @Test
    @DisplayName("★ 区切りが全角スペース・タブ・コロンでも読める")
    void toleratesDifferentSeparators() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                たこ焼き
                キャベツ　150g
                たこ\t60g
                天かす:20g
                """);

        assertThat(items.get(0).lines()).hasSize(3);
        assertThat(items.get(0).lines().get(1).name()).isEqualTo("たこ");
        assertThat(items.get(0).lines().get(2).quantity()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("★ 「・」や「-」で始まる箇条書きでも読める")
    void toleratesBulletMarks() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                たこ焼き
                ・キャベツ 150g
                - たこ 60g
                """);

        assertThat(items.get(0).lines()).hasSize(2);
        assertThat(items.get(0).lines().get(0).name()).isEqualTo("キャベツ");
    }

    @Test
    @DisplayName("★ 小数を読む（0.5 個など）")
    void readsDecimals() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                たこ焼き
                卵 0.5個
                """);

        assertThat(items.get(0).lines().get(0).quantity()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("★ 全角の数字でも読む")
    void readsFullWidthDigits() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                たこ焼き
                キャベツ １５０ｇ
                """);

        assertThat(items.get(0).lines().get(0).quantity()).isEqualByComparingTo("150");
    }

    /**
     * ★ 数字で終わる商品名を材料と読み違えないこと（2026-09-16 に実際に踏んだ）。
     *
     * <p>「たこ焼き-2026」「ハイボール 2杯」のような品名は現実にあります。
     * 商品名が材料の行に化けると、<b>その品まるごとが登録対象から外れます</b>
     * （商品名なしのカードになり、照合できず登録されない）。
     * 数字の前に区切りを必ず要求することで防いでいます。
     */
    @Test
    @DisplayName("★ 数字で終わる商品名を材料と誤読しない")
    void aDishNameEndingInDigitsIsNotReadAsAnIngredient() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                たこ焼き-2026
                キャベツ 150g
                """);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).name()).isEqualTo("たこ焼き-2026");
        assertThat(items.get(0).lines()).hasSize(1);
    }

    // ---------------------------------------------------------------- 読めない行

    /**
     * ★ 読めない行を捨てないこと。
     *
     * <p>「ひとつかみ」「適量」のような書き方は現実にあります。
     * ここで黙って捨てると、人は<b>ノートにあった材料が消えたことに気づけません</b>。
     * 材料名だけの行として残し、確認画面で分量を入れてもらいます。
     */
    @Test
    @DisplayName("★ 分量が読めない行も、材料名だけ残す（黙って捨てない）")
    void keepsLinesWhoseQuantityCannotBeRead() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                たこ焼き
                キャベツ 150g
                紅しょうが ひとつかみ
                """);

        assertThat(items.get(0).lines()).hasSize(2);
        assertThat(items.get(0).lines().get(1).name()).isEqualTo("紅しょうが ひとつかみ");
        assertThat(items.get(0).lines().get(1).quantity()).isNull();
    }

    // ---------------------------------------------------------------- 境界

    @Test
    @DisplayName("空のテキストなら 0 品")
    void emptyTextYieldsNothing() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   \n\n  ")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    /**
     * 材料だけ貼られたとき（商品名を書き忘れた）。
     * 名前なしの 1 品として返し、確認画面で商品を選んでもらいます。
     * 捨ててしまうと「貼ったのに何も出てこない」になります。
     */
    @Test
    @DisplayName("★ 商品名が無くても、材料だけで 1 品として拾う")
    void ingredientsWithoutADishStillBecomeOneCard() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                キャベツ 150g
                豚バラ 40g
                """);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).name()).isNull();
        assertThat(items.get(0).lines()).hasSize(2);
    }

    @Test
    @DisplayName("商品名だけで材料が無い行は品として出さない")
    void aDishWithoutIngredientsIsDropped() {
        assertThat(parser.parse("肉玉米粉そば\n")).isEmpty();
    }
}
