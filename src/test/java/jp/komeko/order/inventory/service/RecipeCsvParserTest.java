package jp.komeko.order.inventory.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV からレシピを読む（設計 ト09d 842:10558）。
 *
 * <p>Figma のカードにこう書いてあります——
 * 「前職のエクセルをそのまま。<b>AI を通さないので、いちばん正確です</b>」
 * 「列は 商品名・材料・分量 があれば OK」。
 *
 * <p><b>列の順番を決め打ちしません。</b>見出し行から探します。
 * 前職のエクセルがどんな列構成かはこちらで決められないので、
 * 「商品名・材料・分量にあたる列がどこかにあればよい」という読み方にします。
 *
 * <p>Spring を起動しない素の JUnit で書きます。
 */
@DisplayName("CSV のレシピ読み取り")
class RecipeCsvParserTest {

    private final RecipeCsvParser parser = new RecipeCsvParser();

    @Test
    @DisplayName("★ 商品名・材料・分量の 3 列を読む")
    void readsTheThreeColumns() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                商品名,材料,分量
                肉玉米粉そば,キャベツ,150
                肉玉米粉そば,豚バラ,40
                豚平焼き,卵,2
                """);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).name()).isEqualTo("肉玉米粉そば");
        assertThat(items.get(0).lines()).hasSize(2);
        assertThat(items.get(1).name()).isEqualTo("豚平焼き");
    }

    /**
     * ★ 列の順番も、余分な列も、こちらでは決められません。
     * 前職のエクセルがどう並んでいるかは分からないので、見出しで探します。
     */
    @Test
    @DisplayName("★ 列の順番が違っても、余分な列があっても読む")
    void findsColumnsByHeaderNotByPosition() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                分量,売価,材料,原価,商品名
                150,1180,キャベツ,312,肉玉米粉そば
                """);

        assertThat(items.get(0).name()).isEqualTo("肉玉米粉そば");
        assertThat(items.get(0).lines().get(0).name()).isEqualTo("キャベツ");
        assertThat(items.get(0).lines().get(0).quantity()).isEqualByComparingTo("150");
    }

    @Test
    @DisplayName("★ 見出しの言い回しがゆれても拾う（品名・食材・量）")
    void toleratesHeaderWording() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                品名,食材,量
                たこ焼き,たこ,60
                """);

        assertThat(items.get(0).name()).isEqualTo("たこ焼き");
        assertThat(items.get(0).lines().get(0).name()).isEqualTo("たこ");
    }

    /**
     * エクセルでよくある形。商品名を 1 行目だけ書いて、以降は空欄。
     * 空欄なら「直前の商品の続き」と読みます。
     */
    @Test
    @DisplayName("★ 商品名が空欄の行は、直前の商品の続きとして読む")
    void blankDishNameContinuesThePrevious() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                商品名,材料,分量
                肉玉米粉そば,キャベツ,150
                ,豚バラ,40
                ,米粉,120
                """);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).lines()).hasSize(3);
    }

    @Test
    @DisplayName("★ 分量が読めない行も材料名だけ残す（黙って捨てない）")
    void keepsRowsWhoseQuantityCannotBeRead() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                商品名,材料,分量
                たこ焼き,紅しょうが,ひとつかみ
                """);

        assertThat(items.get(0).lines()).hasSize(1);
        assertThat(items.get(0).lines().get(0).quantity()).isNull();
    }

    @Test
    @DisplayName("引用符でくくられた値とカンマを読む")
    void readsQuotedValues() {
        List<RecipeTextParser.ParsedItem> items = parser.parse("""
                商品名,材料,分量
                "肉玉、米粉そば","キャベツ",150
                """);

        assertThat(items.get(0).name()).isEqualTo("肉玉、米粉そば");
        assertThat(items.get(0).lines().get(0).name()).isEqualTo("キャベツ");
    }

    @Test
    @DisplayName("タブ区切り（エクセルからの直貼り）でも読む")
    void readsTabSeparated() {
        List<RecipeTextParser.ParsedItem> items = parser.parse(
                "商品名\t材料\t分量\nたこ焼き\tたこ\t60\n");

        assertThat(items.get(0).lines().get(0).name()).isEqualTo("たこ");
    }

    // ---------------------------------------------------------------- 境界

    @Test
    @DisplayName("空なら 0 品")
    void emptyYieldsNothing() {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    /**
     * ★ 見出しが見つからないときに黙って 0 件を返さないこと。
     *
     * <p>「取り込んだのに何も出てこない」がいちばん困ります。
     * 列が分からないなら、<b>読めなかったと分かる形</b>で例外にして、
     * 画面に理由を出します。
     */
    @Test
    @DisplayName("★ 商品名・材料・分量の列が見つからなければ、理由を言って止まる")
    void missingColumnsFailLoudly() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("売価,原価\n1180,312\n")).getMessage())
                .contains("商品名");
    }
}
