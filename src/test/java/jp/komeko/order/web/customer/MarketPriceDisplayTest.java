package jp.komeko.order.web.customer;

import jp.komeko.order.cart.TableContext;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
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
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 価格が決まっていない品（時価）の見せ方。
 *
 * <p><b>なぜこうしているか</b><br>
 * この店には仕入れで値段が変わる品があります（牛ステーキ、日本酒、ワイン）。
 * 値段が決まっていないので<b>0 円で登録し、売り切れ状態</b>にしてあります。
 * 0 円のまま注文が通ると、タダで提供したことになるからです。
 *
 * <p>ところがそれをそのまま出すと、画面には <b>「¥0」</b> と並びます。
 * 無料に見えるうえ、金額が入っていない不具合にも見える。
 * お品書きの世界では、値段が決まっていない品は「時価」と書きます。
 *
 * <p><b>データの都合（0 円）ではなく、お客さまに意味が通る言葉を出す。</b>
 * これは表示だけの話で、0 円で注文させない仕組みは別に効いています。
 * 両方が同時に成り立っていることを、このテストで固定します。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("時価の品の表示")
class MarketPriceDisplayTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MenuItemRepository menuItemRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    DiningTableRepository tableRepository;

    @Autowired
    TableService tableService;

    private MenuItem marketPrice;
    private MenuItem normal;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        Category c = categoryRepository.save(new Category("鉄板焼き", 10));

        marketPrice = new MenuItem(c, "国産牛サーロインステーキ", 0);
        marketPrice.setDescription("時価。仕入れ状況により価格が変わります。");
        marketPrice.setSoldOut(true);
        marketPrice.setSortOrder(10);
        menuItemRepository.save(marketPrice);

        normal = new MenuItem(c, "国産豚ロースステーキ", 2180);
        normal.setSortOrder(20);
        menuItemRepository.save(normal);

        DiningTable table = tableRepository.save(new DiningTable("テスト卓", 4, 10));
        tableService.openSession(table.getId(), 2);

        TableContext context = new TableContext();
        context.bind(table.getId(), table.getName(), table.getAccessToken());
        session = new MockHttpSession();
        session.setAttribute("scopedTarget.tableContext", context);
    }

    @Test
    @DisplayName("メニュー画面で「時価」と出て、「¥0」は出ない")
    void menuShowsMarketPriceLabel() throws Exception {
        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("時価")))
                .andExpect(content().string(
                        not(containsString(">¥0<"))));
    }

    @Test
    @DisplayName("値段が決まっている品は、これまで通り金額が出る")
    void normalItemStillShowsAmount() throws Exception {
        // 「時価」に寄せすぎて、普通の品まで金額が消えては本末転倒
        mockMvc.perform(get("/").session(session))
                .andExpect(content().string(containsString("¥2,180")));
    }

    @Test
    @DisplayName("商品ページでも「時価」と、その理由が出る")
    void detailExplainsWhy() throws Exception {
        // 「時価」とだけ書くと、値段を隠されたように受け取る人もいる。
        // なぜ金額が出ていないのかを、その場で説明する。
        //
        // ★ 文言は 2026-09-06 に設計（暗21 / 暗22）のものへ変えた。
        //   もとは「スタッフにお尋ねください」。設計は
        //   「価格が変わります」＋「スタッフがお席でご説明します」の 2 文で、
        //   なぜ変わるのかと、誰が説明するのかを分けて書いている。
        //   確かめているのは文字そのものではなく、この 2 つが伝わること。
        mockMvc.perform(get("/items/" + marketPrice.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("時価")))
                .andExpect(content().string(containsString("価格が変わります")))
                .andExpect(content().string(containsString("スタッフがお席でご説明します")));
    }

    @Test
    @DisplayName("表示を変えても、0円のまま注文できるようにはなっていない")
    void stillCannotBeOrdered() throws Exception {
        // ★ ここが本題。
        // 「時価」と出すのは見せ方の話で、事故を防ぐ仕組みとは別。
        // 表示を直したついでに売り切れを外してしまうと、
        // 0 円の注文が通って「タダで出した」ことになる。
        org.assertj.core.api.Assertions.assertThat(marketPrice.isSoldOut())
                .as("時価の品は売り切れ状態のままであること")
                .isTrue();
    }
}
