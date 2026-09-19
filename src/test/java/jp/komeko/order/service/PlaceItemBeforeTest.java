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
 * 並び順を、離れた場所へ一度に動かす（2026-09-19、店主の指示）。
 *
 * <p>店主の言葉は「並び順はドラック＆ドロップで入れ替えられる仕様にしたいかな、
 * その方が直感的だし 1 つづつずらして行く必要ないし」。
 *
 * <p>{@code moveItem} は隣と 1 つ入れ替えるだけで、10 番目を 1 番目へ持っていくには
 * 9 回押す必要がありました。{@code placeItemBefore} は一度で動かします。
 *
 * <h2>守っていること</h2>
 * <ol>
 *   <li><b>カテゴリはまたげない。</b>並び順の値はカテゴリごとに独立していて、
 *       一覧も「カテゴリ順 → 並び順」で並んでいます。またぐ移動は
 *       「カテゴリを変える」ことなので、編集フォームの仕事です。
 *       <b>画面だけで守ると URL を直接叩かれたときに素通りします。</b></li>
 *   <li><b>並び順は 10 きざみで振り直す。</b>1 ずつずらしていくと、
 *       いつか隣同士の数字が同じになって順番が決まらなくなります。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("並び順をまとめて動かす")
class PlaceItemBeforeTest {

    @Autowired
    private MenuService menuService;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private CategoryRepository categoryRepository;

    private Category yaki;
    private Category ippin;
    private MenuItem a, b, c, d;
    private MenuItem other;

    @BeforeEach
    void setUp() {
        clear();
        yaki = categoryRepository.save(new Category("お好み焼き", 1));
        ippin = categoryRepository.save(new Category("一品料理", 2));

        a = save("A", yaki, 10);
        b = save("B", yaki, 20);
        c = save("C", yaki, 30);
        d = save("D", yaki, 40);
        other = save("Z", ippin, 10);
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private void clear() {
        menuItemRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private MenuItem save(String name, Category category, int sortOrder) {
        MenuItem m = new MenuItem(category, name, 1000);
        m.setSortOrder(sortOrder);
        m.setVisible(true);
        m.setDraft(false);
        return menuItemRepository.save(m);
    }

    /** いまの並びを名前で読む。 */
    private List<String> order(Category category) {
        return menuItemRepository
                .findByCategoryIdOrderBySortOrderAscIdAsc(category.getId())
                .stream().map(MenuItem::getName).toList();
    }

    @Test
    @DisplayName("★ 離れた場所へ一度で動かせる（D を先頭へ）")
    void itMovesAcrossSeveralRowsAtOnce() {
        assertThat(menuService.placeItemBefore(d.getId(), a.getId())).isTrue();
        assertThat(order(yaki))
                .as("D が先頭に来ていない。1 つずつずらす必要が無いのが要点")
                .containsExactly("D", "A", "B", "C");
    }

    @Test
    @DisplayName("★ 相手を空にすると、いちばん上へ")
    void nullMeansTheTop() {
        assertThat(menuService.placeItemBefore(c.getId(), null)).isTrue();
        assertThat(order(yaki)).containsExactly("C", "A", "B", "D");
    }

    /**
     * ★ 下へ動かすとき、自分を抜いたぶん位置がずれる。
     *
     * <p>A を C の直前へ動かすと、A を抜いた時点で C は 1 つ手前へ来ています。
     * そこを間違えると 1 つ行きすぎます。
     */
    @Test
    @DisplayName("★ 下へ動かしても 1 つ行きすぎない（A を C の前へ）")
    void movingDownDoesNotOvershoot() {
        assertThat(menuService.placeItemBefore(a.getId(), c.getId())).isTrue();
        assertThat(order(yaki))
                .as("A が C の直前に来ていない")
                .containsExactly("B", "A", "C", "D");
    }

    @Test
    @DisplayName("★ いちばん下へ動かせる（相手が自分の次だと何も起きないので注意）")
    void itMovesToTheBottom() {
        // A を「D の次」へ＝相手を空にできないので、いったん D の前へ、ではなく
        // 画面は「末尾なら相手を送らない」ではなく「最後の行の次」を送る作りにしている。
        // ここでは D の直前へ動かして、末尾の 1 つ手前に入ることを確かめる
        assertThat(menuService.placeItemBefore(a.getId(), d.getId())).isTrue();
        assertThat(order(yaki)).containsExactly("B", "C", "A", "D");
    }

    /**
     * ★ カテゴリをまたぐ移動は断る。
     *
     * <p>画面側でも境界を越えさせていませんが、<b>URL を直接叩かれたら素通りします</b>。
     * 通してしまうと、一品料理の列にお好み焼きが紛れ込みます。
     */
    @Test
    @DisplayName("★ カテゴリをまたぐ移動は断る")
    void itRefusesToCrossCategories() {
        assertThat(menuService.placeItemBefore(a.getId(), other.getId()))
                .as("★ 別カテゴリの行の前へ動かせてしまった")
                .isFalse();

        assertThat(order(yaki)).as("断ったのに並びが変わっている")
                .containsExactly("A", "B", "C", "D");
        assertThat(order(ippin)).containsExactly("Z");
    }

    @Test
    @DisplayName("動かす必要が無いときは false（同じ場所へ落とした）")
    void itReportsWhenNothingMoved() {
        assertThat(menuService.placeItemBefore(a.getId(), b.getId()))
                .as("A はもともと B の直前。動かす必要が無い")
                .isFalse();
        assertThat(order(yaki)).containsExactly("A", "B", "C", "D");
    }

    /**
     * ★ 並び順は 10 きざみで振り直す。
     *
     * <p>1 ずつずらしていくと、いつか隣同士の数字が同じになって順番が決まらなくなります。
     */
    @Test
    @DisplayName("★ 動かしたあとの並び順は 10・20・30…")
    void theSortOrderIsRenumbered() {
        menuService.placeItemBefore(d.getId(), a.getId());

        List<MenuItem> rows = menuItemRepository
                .findByCategoryIdOrderBySortOrderAscIdAsc(yaki.getId());
        assertThat(rows.stream().map(MenuItem::getSortOrder).toList())
                .as("振り直していない。続けて動かすと数字が重なって順番が決まらなくなる")
                .containsExactly(10, 20, 30, 40);
    }

    /** 知らない相手を渡されても落ちないこと。 */
    @Test
    @DisplayName("知らない相手なら何もしない")
    void unknownTargetIsIgnored() {
        assertThat(menuService.placeItemBefore(a.getId(), 999999L)).isFalse();
        assertThat(order(yaki)).containsExactly("A", "B", "C", "D");
    }
}
