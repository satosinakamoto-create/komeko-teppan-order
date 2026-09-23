package jp.komeko.order.web.kitchen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 厨房ボードの ✕ は、押したら必ず確認が開くこと。
 *
 * <p><b>このテストが守っているもの＝「押しても何も起きないボタン」を作らないこと。</b>
 *
 * <p>2026-09-23 に、調理済みレーンの ✕ だけ確認モーダルを描き忘れました。
 * 画面には ✕ が並んでいるのに、押しても何も開きません。
 * <b>見た目では気づけません</b>——ボタンはちゃんと見えているからです。
 * 現場では「反応しない画面」として不信を招く、いちばん質の悪い壊れ方です。
 *
 * <p>そこで {@code data-open-modal} が指す先が<b>本当に存在するか</b>を照合します。
 * レーンを増やしたり、モーダルを描く範囲を変えたりしたときに、ここが先に落ちます。
 *
 * <p>{@code @Transactional} を付けないのは、{@code open-in-view: false} の本番と
 * 同じトランザクション境界で描画させるためです。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("厨房ボードの ✕ と確認モーダルの対応")
class KitchenServeCancelTest {

    @Autowired
    private MockMvc mockMvc;

    /** ✕ が「どのモーダルを開くか」。 */
    private static final Pattern OPENS =
            Pattern.compile("data-open-modal=\"(kline-cancel-[0-9]+)\"");

    /** 実際に描かれているモーダル。 */
    private static final Pattern DIALOGS =
            Pattern.compile("<dialog[^>]*id=\"(kline-cancel-[0-9]+)\"");

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★★ ✕ が指す確認モーダルは、すべて実在する")
    void everyCancelButtonHasItsDialog() throws Exception {
        String html = mockMvc.perform(get("/kitchen"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Set<String> opens = new HashSet<>();
        Matcher a = OPENS.matcher(html);
        while (a.find()) {
            opens.add(a.group(1));
        }

        Set<String> dialogs = new HashSet<>();
        Matcher b = DIALOGS.matcher(html);
        while (b.find()) {
            dialogs.add(b.group(1));
        }

        // 片方が空だと、対応を確かめずに素通りします。
        // 盤面に品が 1 つも無い日でも落ちないよう、あることを前提にはしません。
        if (opens.isEmpty()) {
            return;
        }

        assertThat(dialogs)
                .as("★★ 押しても何も開かない ✕ がある（確認モーダルを描き忘れている）")
                .containsAll(opens);
    }
}
