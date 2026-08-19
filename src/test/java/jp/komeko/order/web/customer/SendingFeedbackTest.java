package jp.komeko.order.web.customer;

import jp.komeko.order.cart.TableContext;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.service.TableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「押した瞬間に反応が返る」ための仕掛けが、待たされる画面に載っていることを確かめる。
 *
 * <p><b>なぜテストするのか</b><br>
 * 公開デモは 1 リクエストにおよそ 1 秒かかります（無料枠の CPU）。
 * 手元では 60ms なので、開発中はこの差にまったく気づけません。
 * ブラウザは次のページが届くまで何も描き替えないので、
 * 押しても変わらない 1 秒は「待ち時間」ではなく<b>故障</b>に見えます。
 *
 * <p>実際、いちばん最初に触る「人数を決める」画面で
 * 「反応が無くて固まる」と言われました。
 *
 * <p><b>速さは変えられません。</b>サーバ側の固定費だからです。
 * できるのは「受け付けた」と即座に返すことだけで、
 * そのための材料（{@code customer.js} と {@code data-sending-label}）が
 * 画面に載っているかを固定します。
 *
 * <p>これが外れても<b>画面は正常に動きます</b>。ただ遅く見えるだけなので、
 * 目でも自動でも気づけません。だから明示的に見張ります。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("送信中の反応")
class SendingFeedbackTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DiningTableRepository tableRepository;

    @Autowired
    TableService tableService;

    private DiningTable table;

    @BeforeEach
    void setUp() {
        table = tableRepository.save(new DiningTable("テスト卓", 4, 10));
    }

    @Test
    @DisplayName("人数を決める画面に、送信中の文言と JavaScript が載っている")
    void guestCountScreenHasFeedback() throws Exception {
        mockMvc.perform(get("/t/" + table.getAccessToken()))
                .andExpect(status().isOk())
                // 押した瞬間にボタンの文字が変わる
                .andExpect(content().string(containsString("data-sending-label")))
                // それを実行するスクリプト。レイアウトで読み込んでいる。
                // ファイル名に内容ハッシュが付く（/js/customer-9f3c….js）ので、
                // 拡張子まで含めた完全一致では通らない。
                .andExpect(content().string(containsString("/js/customer")));
    }

    @Test
    @DisplayName("注文リストの画面にも載っている（二重送信を防ぐ場所）")
    void cartScreenHasFeedback() throws Exception {
        // 反応が無いともう一度押される。注文確定を 2 回送れば同じ品が 2 つ入る。
        // 遅さは、それ自体が事故の原因になる。
        MockHttpSession session = new MockHttpSession();
        tableService.openSession(table.getId(), 2);
        TableContext context = new TableContext();
        context.bind(table.getId(), table.getName(), table.getAccessToken());
        session.setAttribute(
                "scopedTarget.tableContext", context);

        mockMvc.perform(get("/cart").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/js/customer")));
    }

    @Test
    @DisplayName("9名以上の決定ボタンは、最初は主役の色にしない")
    void otherSubmitStartsSecondary() throws Exception {
        // 人数を入れる前から黒いボタンが置いてあると、
        // 「まずこれを押すもの」に見えて空のまま押される。
        // 色を付けるのは値が入ってから（customer.js が btn--primary を足す）。
        String html = mockMvc.perform(get("/t/" + table.getAccessToken()))
                .andReturn().getResponse().getContentAsString();

        int start = html.indexOf("id=\"guestCountOtherSubmit\"");
        org.assertj.core.api.Assertions.assertThat(start)
                .as("決定ボタンに id が付いていること（JS がこれを見て色を変える）")
                .isGreaterThanOrEqualTo(0);

        String tag = html.substring(start, html.indexOf(">", start));
        org.assertj.core.api.Assertions.assertThat(tag)
                .as("初期状態で btn--primary が付いていないこと")
                .doesNotContain("btn--primary");
    }

    @Test
    @DisplayName("customer.js はレイアウトで 1 回だけ読み込む")
    void scriptIsLoadedOnce() throws Exception {
        // もとは商品詳細と伝票が個別に読み込んでいた。
        // レイアウトへ移したときに消し忘れると 2 回読み込まれ、
        // 伝票画面の「数秒ごとの状態確認」が二重に走る。
        String html = mockMvc.perform(get("/t/" + table.getAccessToken()))
                .andReturn().getResponse().getContentAsString();

        // ファイル名には内容ハッシュが付く（/js/customer-9f3c….js）ので、
        // 区切りの直後の 1 文字までで数える
        int count = html.split("/js/customer[-.]", -1).length - 1;
        org.assertj.core.api.Assertions.assertThat(count)
                .as("customer.js の読み込みは 1 回だけであること")
                .isEqualTo(1);
    }
}
