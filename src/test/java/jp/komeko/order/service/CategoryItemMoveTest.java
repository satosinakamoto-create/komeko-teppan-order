package jp.komeko.order.service;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.MenuItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * カテゴリの編集画面から商品を出し入れする（2026-09-20）。
 *
 * <p>ここで守るのは<b>並び番号の壊れ方</b>です。
 * 並び番号が壊れても例外は出ず、画面もふつうに出ます。
 * 気づけるのは「ドラッグしても順番が変わらない」と言われたときです。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("商品をまとめて別のカテゴリへ移す")
class CategoryItemMoveTest {

    @Autowired
    private MenuService menuService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;

    private Long from;
    private Long to;
    private Long a1, a2, a3;

    @BeforeEach
    void setUp() {
        clear();
        Category f = categoryRepository.save(new Category("鉄板おつまみ", 10));
        Category t = categoryRepository.save(new Category("鉄板麺", 20));
        from = f.getId();
        to = t.getId();

        a1 = item(f, "牛すじ焼き", 10);
        a2 = item(f, "せせり焼き", 20);
        a3 = item(f, "砂肝焼き", 30);
        item(f, "残るもの", 40);

        item(t, "肉玉米粉そば", 10);
        item(t, "焼きそば", 20);
    }

    private Long item(Category c, String name, int sort) {
        MenuItem m = new MenuItem(c, name, 800);
        m.setVisible(true);
        m.setDraft(false);
        m.setSortOrder(sort);
        return menuItemRepository.save(m).getId();
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private void clear() {
        menuItemRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private List<String> namesIn(Long categoryId) {
        return menuItemRepository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId)
                .stream().map(MenuItem::getName).toList();
    }

    private List<Integer> ordersIn(Long categoryId) {
        return menuItemRepository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId)
                .stream().map(MenuItem::getSortOrder).toList();
    }

    /** ★ 行き先の末尾に、10 きざみで順に入ること。 */
    @Test
    @DisplayName("★ 行き先の末尾に 30 / 40 / 50 で入る")
    void theyLandAtTheEndInOrder() {
        assertThat(menuService.moveItemsToCategory(List.of(a1, a2, a3), to)).isEqualTo(3);

        assertThat(ordersIn(to))
                .as("★ 行き先の並び番号が 10,20,30,40,50 になっていない。"
                        + "1 回取った番号を全員に使うと、全員が同じ番号になる")
                .containsExactly(10, 20, 30, 40, 50);
    }

    /** ★ 移した 3 品の相対順が、元のままであること。 */
    @Test
    @DisplayName("★ 移したあとも 3 品の順番は元のまま")
    void theirOrderIsKept() {
        menuService.moveItemsToCategory(List.of(a1, a2, a3), to);

        assertThat(namesIn(to))
                .containsExactly("肉玉米粉そば", "焼きそば", "牛すじ焼き", "せせり焼き", "砂肝焼き");
    }

    /**
     * ★ 並び番号が重なっていないこと。
     *
     * <p>重なると、そのカテゴリでは以後ドラッグしても上下ボタンを押しても
     * 順番が決まりません。押しても何も起きないだけで、例外は出ません。
     */
    @Test
    @DisplayName("★ 行き先に同じ並び番号が無い")
    void noDuplicateOrders() {
        menuService.moveItemsToCategory(List.of(a1, a2, a3), to);

        List<Integer> orders = ordersIn(to);
        assertThat(orders.stream().distinct().count())
                .as("★ 並び番号が重なった。以後その行き先では並べ替えが効かなくなる")
                .isEqualTo(orders.size());
    }

    /**
     * ★ 元のカテゴリは詰め直さないこと。
     *
     * <p>10, 20, 30, 40 から 10/20/30 を抜いて 40 が残るのは正常です。
     * 詰め直す処理を書くと、余計な UPDATE が走るうえ、
     * 「動かしていないものが動く」ことになります。
     */
    @Test
    @DisplayName("★ 元のカテゴリの残りは動かさない")
    void theSourceIsNotRenumbered() {
        menuService.moveItemsToCategory(List.of(a1, a2, a3), to);

        assertThat(namesIn(from)).containsExactly("残るもの");
        assertThat(ordersIn(from))
                .as("残った商品の番号まで振り直している")
                .containsExactly(40);
    }

    /**
     * ★ 行き先が今いるカテゴリでも壊れないこと。
     *
     * <p>飛ばさないと、その商品自身を含んだ最大値が使われ、
     * 「移していないのに自分のカテゴリの末尾へ黙って飛ぶ」ことになります。
     */
    @Test
    @DisplayName("★ すでに行き先にいる商品は動かさない")
    void itemsAlreadyThereAreSkipped() {
        assertThat(menuService.moveItemsToCategory(List.of(a1, a2), from))
                .as("すでにそのカテゴリにいるのに移したことになっている")
                .isEqualTo(0);

        assertThat(ordersIn(from))
                .as("★ 移していないのに並び番号が動いた")
                .containsExactly(10, 20, 30, 40);
    }

    /** ★ 何も選ばずに呼んでも例外にならないこと。 */
    @Test
    @DisplayName("★ 1 つも選んでいなければ何もしない")
    void nothingSelectedIsSafe() {
        assertThat(menuService.moveItemsToCategory(List.of(), to)).isEqualTo(0);
        assertThat(menuService.moveItemsToCategory(null, to)).isEqualTo(0);
        assertThat(ordersIn(to)).containsExactly(10, 20);
    }

    /**
     * ★ 書きかけは draft と visible を両方落とすこと。
     *
     * <p>どちらか片方だけでは、お客さまの画面に出ます。
     * 2026-09-07 に実際に出しました。
     */
    @Test
    @DisplayName("★ 名前だけの商品は「書きかけ」かつ「非掲載」で作る")
    void draftsAreHiddenTwice() {
        MenuItem m = menuService.createDraftItem(from, "  秋の鉄板きのこ  ");

        assertThat(m.getName()).as("前後の空白を落としていない").isEqualTo("秋の鉄板きのこ");
        assertThat(m.isDraft()).as("★ draft が立っていない").isTrue();
        assertThat(m.isVisible())
                .as("★ visible が true のまま。draft だけでは、"
                        + "visible しか見ていない門（MenuController.item など）を通ってしまう")
                .isFalse();
        assertThat(m.getSortOrder())
                .as("★ 並び順が 0 のまま。看板メニューの上に割り込む")
                .isEqualTo(50);
    }

    /** ★ 同じ名前の商品を、全角半角・大文字小文字・前後の空白をまたいで見つけること。 */
    @Test
    @DisplayName("★ 同じ名前の商品を見つける（全角半角・空白を吸収）")
    void sameNameIsFoundAcrossWidthAndSpaces() {
        assertThat(menuService.findSameNameItem("焼きそば"))
                .as("そのままの名前で見つからない").isNotNull();
        assertThat(menuService.findSameNameItem("  焼きそば  "))
                .as("前後の空白で見つからない").isNotNull();
        assertThat(menuService.findSameNameItem("焼きそば"))
                .extracting(MenuItem::getName).isEqualTo("焼きそば");
        assertThat(menuService.findSameNameItem("まだ無い品"))
                .as("無い名前で何か返している").isNull();
    }

    /**
     * ★ 記号は吸収しないこと。
     *
     * <p>{@code AliasText.normalize} は {@code （）} などを落とすので、
     * 流用すると「生ビール（中）」と「生ビール中」が同名扱いになり、
     * <b>正しい商品を作れなくなります。</b>
     */
    @Test
    @DisplayName("★ 記号まで落として同名扱いにしない")
    void punctuationIsNotStripped() {
        Category c = categoryRepository.findById(from).orElseThrow();
        item(c, "生ビール（中）", 50);

        assertThat(menuService.findSameNameItem("生ビール中"))
                .as("★ 記号を落として同名扱いにしている。"
                        + "「生ビール（中）」があると「生ビール中」を作れなくなる")
                .isNull();
    }
}
