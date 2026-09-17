package jp.komeko.order.web.hall;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DailyCounterRepository;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
import jp.komeko.order.service.ShopSettingService;
import jp.komeko.order.service.TableService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ホール・会計ボードを設計 現02（437:2305）に合わせた（2026-09-07）。
 *
 * <p><b>この画面だけ 1 段小さく組んでいます。</b>
 * 立って一瞬見る画面なので、数字（卓数・金額）だけを大きく残し、
 * ラベルと注記を落として、1 画面に入る卓の数を増やすのが狙いです。
 *
 * <p><b>伝票カードは .card をやめました。</b>
 * 見出し帯・本体・足の 3 段に線で区切られていると、
 * 卓名から金額まで目が 2 回止まります。設計は平らな 1 枚で、
 * 金額と注記も横に並べて 1 行ぶん詰めています。
 *
 * <p>{@code @Transactional} を付けていないのは、{@code open-in-view: false} の
 * 本番と同じ形で描画させるためです（{@code HallClosingBadgeTest} と同じ理由）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ホール・会計ボード（設計 現02）")
class HallBoardDesignTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TableService tableService;
    @Autowired
    private ShopSettingService shopSettingService;
    @Autowired
    private DiningTableRepository diningTableRepository;
    @Autowired
    private TableSessionRepository tableSessionRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private DailyCounterRepository dailyCounterRepository;

    private static final Path BOARD = Path.of("src/main/resources/templates/hall/board.html");
    private static final Path CSS = Path.of("src/main/resources/static/css/app.css");

    @BeforeEach
    void setUp() {
        clearAll();
        // 何時に走らせても同じ結果になるようにそろえる
        ShopSetting setting = shopSettingService.current();
        setting.setAcceptingOrders(true);
        setting.setOpenTime(LocalTime.MIN);
        setting.setLastOrderTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setLateNightSurchargePercent(0);
        shopSettingService.save(setting);

        DiningTable table = diningTableRepository.save(new DiningTable("3番テーブル", 4, 10));
        tableService.openSession(table.getId(), 2);
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    /** 参照している側から先に消す（外部キー制約）。 */
    private void clearAll() {
        orderRepository.deleteAll();
        tableSessionRepository.deleteAll();
        diningTableRepository.deleteAll();
        menuItemRepository.deleteAll();
        categoryRepository.deleteAll();
        dailyCounterRepository.deleteAllInBatch();
    }

    private String board() throws Exception {
        return mockMvc.perform(get("/hall"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 伝票カードが平らな 1 枚で描ける（見出し帯と足に割らない）")
    void billCardIsFlat() throws Exception {
        String html = board();

        assertThat(html).as("伝票カードの目印が無い").contains("billcard");
        assertThat(html).as("金額の行が無い").contains("billcard__amount");
        assertThat(html).contains("3番テーブル");
        // 注記（チャージ／深夜料金）まで描けていること。
        // th:if を書き間違えると、ここだけ静かに消える
        assertThat(html).as("チャージの注記が出ていない").contains("テーブルチャージ ¥");

        // 在席の伝票の並びに .card が混ざっていないこと。
        // 混ざると 1 列だけ枠と余白が違って見える。
        // ★ 引用符ごと比べる。"card__head" だけで探すと billcard__head に
        //   部分一致して、必ず失敗する（.label / .btn でも同じ罠を踏んでいる）
        // 区切りは「在席の列のはじまり」から「本日の会計済み」まで。
        // 2026-09-09 に「卓ごとの注文」、09-11 に「空席」の枠、
        // 09-12 に「在席の伝票」の見出しごと 3 列へ組み替えたので、
        // 文字ではなく列のクラス名を目印にする。
        // 会計済みの一覧は会計取消の入口なので残り続ける
        int from = html.indexOf("lane--seated");
        int to = html.indexOf("本日の会計済み", from);
        assertThat(from).isGreaterThan(0);
        assertThat(to).isGreaterThan(from);
        assertThat(html.substring(from, to)).doesNotContain("\"card__head\"");
    }

    @Test
    @WithMockUser(roles = "STAFF")
    @DisplayName("★ 伝票を見る／お会計への入口が残っている")
    void keepsTheWayIntoTheBill() throws Exception {
        assertThat(board()).contains("伝票を見る／お会計");
        assertThat(board()).contains("/hall/bills/");
    }

    @Test
    @DisplayName("★ 金額と注記は横並び（縦に積むと 1 画面に入る卓が減る）")
    void amountAndNoteShareARow() throws Exception {
        String html = Files.readString(BOARD).replaceAll("(?s)<!--.*?-->", "");
        int at = html.indexOf("billcard__amount");
        assertThat(at).isGreaterThan(0);
        String block = html.substring(at, html.indexOf("</div>", at));
        assertThat(block).contains("billcard__yen");
        assertThat(block).contains("billcard__note");
        assertThat(Files.readString(CSS).replace("\r\n", "\n"))
                .contains(".hallboard .billcard__amount { display: flex; align-items: center; gap: 24px;");
    }

    @Test
    @DisplayName("★ お会計待ち・調理中の札は残す（会計を切る前に気づくため）")
    void badgesSurvivedTheRedesign() throws Exception {
        String html = Files.readString(BOARD);
        assertThat(html).contains("badge--stop");
        assertThat(html).contains("badge--rec");
        assertThat(html).contains("bill.hasPendingOrders()");
    }

    @Test
    @DisplayName("★ 設計の寸法（見出し32／節20+字間2／カード余白24全周）")
    void designMetrics() throws Exception {
        String css = Files.readString(CSS).replace("\r\n", "\n");

        // ★ 2026-09-17 に 28px → 32px（店主の指摘）。
        //   設計（ト02 725:2103）は 28px だが、設計側の取りこぼしと判断した。
        //   この画面は帯の上下を 20px に厚くしてあり、その理由は
        //   「立って見る画面だけ帯を厚くする」。立って見る画面で題だけ小さいのは
        //   筋が通らない。Figma の他の画面（ダッシュボード・品切れ・残数・
        //   商品・売上）はすべて 32px で、ここだけ 28px だった。
        assertThat(css).contains(".hallboard h1.section-title__text { font-size: 32px; }");
        // .theme-snow が字間を 0 に落とすので、節見出しだけ開け直している
        assertThat(css).contains(".hallboard h2.section-title__text { font-size: 20px; letter-spacing: 2px; }");
        // ★ 数字カード（.stat 28px）はここで見ていたが、2026-09-14 に
        //   カードの段ごと畳んだ（HallStatMergeTest）。規則も消してある

        // カードの内側は 24 全周（2026-09-12 に 24/16 から変更。設計 540:3509）。
        // ★ 素の "padding: 24px;" で探さないこと。他の部品にも同じ値があるので、
        //   .hallboard .billcard の宣言ブロックを切り出してから確かめる
        int at = css.indexOf(".hallboard .billcard {");
        assertThat(at).as(".hallboard .billcard の指定が無い").isGreaterThan(0);
        String cardBlock = css.substring(at, css.indexOf("}", at));
        assertThat(cardBlock).contains("padding: 24px;");
        assertThat(cardBlock).contains("border-radius: 6px;");
        // 卓名は設計どおり 24px（18px から上げた）
        assertThat(css).contains(".hallboard .billcard__table { font-size: 24px;");
        // ★ .grid--3 の gap もカードの段と一緒に消えた（2026-09-14）
        // 本文の縦余白 64→32。共通の変数は触らず、この画面だけ絞る
        assertThat(css).contains("--main-pad-y: 32px; --main-pad-x: 24px;");
        assertThat(Files.readString(BOARD)).contains("class=\"hallboard\"");
    }

    @Test
    @DisplayName("★ カードのボタンは設計どおり 44px（2026-09-12 に 48px から変更）")
    void cardButtonFollowsTheDesignHeight() throws Exception {
        // このテストは 2026-09-11 まで逆のこと（48px を守る）を書いていた。
        // 2026-09-12 に「Figma を正とする」とユーザーが決めたので、設計に合わせた。
        //
        // ★ CLAUDE.md の「タップ領域は 48px 以上」は廃止していない。
        //   例外はこのカードのボタン 1 か所だけ。ほかは var(--tap) のまま。
        //   もし店のタブレットで押しにくければ、min-height の行を消せば 48px に戻る
        String css = Files.readString(CSS).replace("\r\n", "\n");

        int at = css.indexOf(".hallboard .billcard .btn {");
        assertThat(at).as(".hallboard .billcard .btn の指定が無い").isGreaterThan(0);
        String block = css.substring(at, css.indexOf("}", at));
        assertThat(block).contains("min-height: 44px;");
        // 文字は 15px（＝基底の .btn と同じ .9375rem）。2026-09-12 に 14px から戻した。
        // 値を 2 か所に持たないなら、この上書き自体を消して .btn に任せてもよい
        assertThat(block).contains("font-size: 15px;");
        assertThat(block).contains("border-radius: 4px;");

        // 例外はここだけ。共通の .btn は 48px（--tap）のまま
        assertThat(css).as("共通のボタンまで小さくしている")
                .contains("min-height: var(--tap);");
    }

    @Test
    @DisplayName("★ .theme-desk はホール・厨房にも付いている（CLAUDE.md の記述を実物で固定）")
    void themeDeskIsOnEveryStaffScreen() throws Exception {
        // 「厨房・ホールには付けない」と長く書かれていたが、layout/staff.html の
        // body は theme-snow theme-desk の固定。付かない前提で .theme-desk に
        // 書くと、気づかないままホールと厨房に漏れる（2026-09-07 に実測で判明）
        String layout = Files.readString(Path.of("src/main/resources/templates/layout/staff.html"));
        assertThat(layout).contains("<body class=\"theme-snow theme-desk\">");
    }
}
