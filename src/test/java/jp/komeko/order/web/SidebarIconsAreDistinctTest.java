package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * サイドバーのアイコンは項目ごとに違うものにする（2026-09-17、店主の指摘）。
 *
 * <p><b>何が起きていたか。</b>「品切れ・残数」と「食材・在庫」が<b>どちらも
 * {@code ic_package}（箱）</b>でした。サイドバーは iPad ではアイコンだけになる
 * （ラベルが消える）ので、その状態では 2 つを見分けられません。
 *
 * <p><b>どちらを替えたか。</b>品切れ・残数のほうです。意味の対応がそのほうが素直で、
 * <b>箱＝在庫</b>は残り、<b>禁止マーク＝出せない</b>が付きます。
 * 逆にすると箱が品切れ側に残り、「箱＝品切れ？」とねじれます。
 *
 * <p><b>同じセットから採ること。</b>Phosphor duotone の {@code Prohibit} です。
 * 店主から Material Symbols の PNG をいただいて実際に当てはめてみましたが、
 * <b>PNG は色を持てない</b>ため、現在地の行で文字だけ緑・アイコンは黒、という
 * ちぐはぐな見え方になりました。作風の違いより、そちらのほうが目立ちます。
 * インライン SVG ＋ {@code fill="currentColor"} という既存の作り（CLAUDE.md）を
 * 崩さないのが肝心です。
 *
 * <p><b>24px で見分けが付くこと。</b>箱は四角い塊、禁止マークは丸＋斜め線で、
 * 形が正反対です。細い線のアイコン（{@code list-numbers} など）は 24px だと
 * 潰れて「灰色の塊」になるので選んでいません。
 */
@DisplayName("サイドバーのアイコンは項目ごとに違う")
class SidebarIconsAreDistinctTest {

    private static final Path LAYOUT =
            Path.of("src/main/resources/templates/layout/staff.html");
    private static final Path ICONS =
            Path.of("src/main/resources/templates/fragments/icons.html");

    private String read(Path p) throws Exception {
        return Files.readString(p).replace("\r\n", "\n");
    }

    /** コメントを落とした本文。コメントの中の名前を数えないため。 */
    private String body(Path p) throws Exception {
        return read(p).replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * ★ 同じアイコンを 2 つの項目に付けない。
     *
     * <p>サイドバーの各項目（{@code .sb__item}）が使っているアイコン名を数えて、
     * 2 回以上出てくるものが無いことを見ます。
     */
    @Test
    @DisplayName("★ サイドバーの項目でアイコンが重複していない")
    void noTwoSidebarItemsShareAnIcon() throws Exception {
        String html = body(LAYOUT);

        // ★ .sb__item に絞ること。上の帯の「未提供」チップも同じ書き方で
        //   アイコンを呼んでいて、そのままだと混ざる。
        //
        // ★ 数えるのは「ラベルの種類」。サイドバーは権限で 2 つの分岐に
        //   分かれていて、同じ項目（売上・スタッフなど）が 2 回書いてある。
        //   同じラベルが 2 回出るのは重複ではない。
        Matcher m = Pattern.compile(
                "<a class=\"sb__item\"(?:(?!</a>).)*?icons :: (ic_[a-z_]+)\\}\"/>"
                        + "<span[^>]*>([^<]+)</span>", Pattern.DOTALL).matcher(html);

        Map<String, List<String>> byIcon = new HashMap<>();
        while (m.find()) {
            String icon = m.group(1);
            String label = m.group(2).trim();
            List<String> labels = byIcon.computeIfAbsent(icon, k -> new ArrayList<>());
            if (!labels.contains(label)) {
                labels.add(label);
            }
        }
        assertThat(byIcon).as("サイドバーの項目が 1 つも拾えていない").isNotEmpty();

        StringBuilder dup = new StringBuilder();
        byIcon.forEach((icon, labels) -> {
            if (labels.size() > 1) {
                dup.append("\n  ").append(icon).append(" → ").append(String.join(" / ", labels));
            }
        });

        assertThat(dup.toString())
                .as("同じアイコンを使っている項目がある。"
                        + "iPad ではラベルが消えてアイコンだけになるので見分けが付かない:%s", dup)
                .isEmpty();
    }

    /** ★ 品切れ・残数は禁止マーク、食材・在庫は箱。 */
    @Test
    @DisplayName("★ 品切れ・残数は禁止マーク、食材・在庫は箱")
    void theTwoScreensGetTheirOwnIcon() throws Exception {
        String html = body(LAYOUT);

        assertThat(html)
                .as("品切れ・残数が禁止マークになっていない")
                .contains("icons :: ic_prohibit}\"/><span>品切れ・残数</span>");
        assertThat(html)
                .as("食材・在庫の箱が変わっている（こちらは据え置き）")
                .contains("icons :: ic_package}\"/><span>食材・在庫</span>");
    }

    /**
     * ★ 足したアイコンも、いままでと同じ作りであること。
     *
     * <p>{@code fill="currentColor"} が無いと、現在地の行で文字だけ緑になり
     * アイコンは黒のまま残ります。これは店主にいただいた PNG を当てはめたときに
     * 実際に起きた見え方です。
     */
    @Test
    @DisplayName("★ 新しいアイコンも currentColor で文字色に追従する")
    void theNewIconFollowsTheTextColour() throws Exception {
        String icons = read(ICONS);

        int at = icons.indexOf("th:fragment=\"ic_prohibit\"");
        assertThat(at).as("ic_prohibit が icons.html に無い").isGreaterThan(0);

        String svg = icons.substring(at, icons.indexOf("</svg>", at));
        assertThat(svg).as("currentColor になっていない").contains("fill=\"currentColor\"");
        assertThat(svg).as("viewBox が他と違う").contains("viewBox=\"0 0 256 256\"");
        assertThat(svg).as("class=\"ic\" が無い").contains("class=\"ic\"");
        // duotone は薄い面（opacity 0.2）と線の 2 枚組
        assertThat(svg).as("duotone の薄い面が無い（線だけの版を貼っている）")
                .contains("opacity=\"0.2\"");
    }

    /** ★ アイコンは 1 つのセットにそろえる。ライセンス表記も残す。 */
    @Test
    @DisplayName("★ Phosphor で統一され、MIT の表記が残っている")
    void everythingStaysInOneSet() throws Exception {
        String icons = read(ICONS);

        assertThat(icons).as("ライセンス表記が消えている（MIT の条件）")
                .contains("MIT License");
        assertThat(icons).as("出どころの記載が消えている")
                .contains("phosphor-icons/core");
        // 画像ファイルを貼っていないこと（色が追従しなくなる）
        assertThat(body(ICONS)).as("img で貼っているアイコンがある").doesNotContain("<img");
    }
}
