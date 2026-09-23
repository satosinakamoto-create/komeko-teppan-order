package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探す欄（.searchbox）の高さを 48px に統一する（2026-09-13、店主指示）。
 *
 * <p><b>経緯</b><br>
 * 素の .searchbox は 64px でした（設計 現04 の値）。ただし品切れ・残数だけ
 * .soldoutpage で 48px に落としており、同じ「探す欄」が画面によって
 * 64（商品・食材）と 48（品切れ）の 2 種類ある状態でした。
 * ボタン（48）・タップの床（48）・サイドバーの項目（48）と並ぶ寸法なので、
 * 48px に一本化します。Figma 07 ページの 7 か所も同じ日に 48 へ更新済み。
 *
 * <p><b>上書きを残さないこと。</b>
 * 素が 48 になった今、.soldoutpage 側の 48px は「同じ値の二重定義」です。
 * 残すと、次に高さを変えるとき片方だけ動く事故になります
 * （食材・在庫の card__head で実際に踏んだのと同じ形）。
 */
@DisplayName("探す欄の高さは 48px に統一")
class SearchBoxHeightTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    @Test
    @DisplayName("★ 素の .searchbox が 48px。品切れ側の同値の上書きは消す")
    void searchBoxIsFortyEightEverywhere() throws Exception {
        String css = Files.readString(CSS).replace("\r\n", "\n");

        int at = css.indexOf(".searchbox {\n");
        assertThat(at).as(".searchbox の本体が無い").isGreaterThan(0);
        String rule = css.substring(at, css.indexOf("}", at));
        assertThat(rule).contains("height: 48px;");

        String bare = css.replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(bare)
                .as("素が 48 になったので、品切れ側の上書きは二重定義")
                .doesNotContain(".soldoutpage .searchbox { height: 48px; }");
    }
}
