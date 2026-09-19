package jp.komeko.order.service;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DiningTableRepository;
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
 * カテゴリと卓の並べ替え（2026-09-19、店主の指示
 * 「商品、カテゴリー、卓にもドラッグ＆ドロップ実装してほしい」）。
 *
 * <p>商品と同じ考え方です。行き先は「どの並びの隣か」で指します——
 * {@code before} があればその直前、無ければ {@code after} の直後。
 * 中身は {@code SortOrderPlacer} に 1 つだけ置いて、3 つで使い回しています。
 *
 * <p><b>どちらも 1 本の並びです。</b>商品のような「またげない境界」（カテゴリ）は
 * ありません。卓はエリア（1F・2F など）で分けていますが、並び順は全体で 1 つです。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("カテゴリと卓の並べ替え")
class PlaceCategoryAndTableTest {

    @Autowired
    private MenuService menuService;
    @Autowired
    private TableService tableService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private DiningTableRepository tableRepository;

    private Long catA, catB, catC;
    private Long tblA, tblB, tblC;

    @BeforeEach
    void setUp() {
        clear();
        catA = categoryRepository.save(new Category("A", 10)).getId();
        catB = categoryRepository.save(new Category("B", 20)).getId();
        catC = categoryRepository.save(new Category("C", 30)).getId();

        tblA = tableRepository.save(new DiningTable("卓A", 4, 10)).getId();
        tblB = tableRepository.save(new DiningTable("卓B", 4, 20)).getId();
        tblC = tableRepository.save(new DiningTable("卓C", 4, 30)).getId();
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private void clear() {
        tableRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private List<String> catOrder() {
        return categoryRepository.findAllByOrderBySortOrderAscIdAsc()
                .stream().map(Category::getName).toList();
    }

    private List<String> tblOrder() {
        return tableRepository.findAllByOrderBySortOrderAscIdAsc()
                .stream().map(DiningTable::getName).toList();
    }

    // ---------------------------------------------------------------- カテゴリ

    @Test
    @DisplayName("★ カテゴリ：離れた場所へ一度で動かせる（C を先頭へ）")
    void categoryMovesAtOnce() {
        assertThat(menuService.placeCategoryNextTo(catC, catA, null)).isTrue();
        assertThat(catOrder())
                .as("C が先頭に来ていない。1 つずつずらす必要が無いのが要点")
                .containsExactly("C", "A", "B");
    }

    /**
     * ★ after で「いちばん下」を指せること。
     *
     * <p>{@code null} を先頭の意味にしていた頃は、下端に落とすと先頭へ飛びました。
     */
    @Test
    @DisplayName("★ カテゴリ：after で直後に入る（いちばん下へ）")
    void categoryAfterPutsItBehind() {
        assertThat(menuService.placeCategoryNextTo(catA, null, catC)).isTrue();
        assertThat(catOrder()).containsExactly("B", "C", "A");
    }

    @Test
    @DisplayName("★ カテゴリ：行き先が無ければ何もしない")
    void categoryNeedsADestination() {
        assertThat(menuService.placeCategoryNextTo(catA, null, null)).isFalse();
        assertThat(catOrder()).containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("★ カテゴリ：動かしたあとの並び順は 10・20・30…")
    void categoryIsRenumbered() {
        menuService.placeCategoryNextTo(catC, catA, null);
        assertThat(categoryRepository.findAllByOrderBySortOrderAscIdAsc()
                .stream().map(Category::getSortOrder).toList())
                .as("振り直していない。続けて動かすと数字が重なって順番が決まらなくなる")
                .containsExactly(10, 20, 30);
    }

    // ---------------------------------------------------------------- 卓

    @Test
    @DisplayName("★ 卓：離れた場所へ一度で動かせる（C を先頭へ）")
    void tableMovesAtOnce() {
        assertThat(tableService.placeTableNextTo(tblC, tblA, null)).isTrue();
        assertThat(tblOrder()).containsExactly("卓C", "卓A", "卓B");
    }

    @Test
    @DisplayName("★ 卓：after で直後に入る（いちばん下へ）")
    void tableAfterPutsItBehind() {
        assertThat(tableService.placeTableNextTo(tblA, null, tblC)).isTrue();
        assertThat(tblOrder()).containsExactly("卓B", "卓C", "卓A");
    }

    /**
     * ★ 下へ動かすとき、自分を抜いたぶん位置がずれる。
     *
     * <p>A を C の直前へ動かすと、A を抜いた時点で C は 1 つ手前へ来ています。
     * そこを間違えると 1 つ行きすぎます。
     */
    @Test
    @DisplayName("★ 卓：下へ動かしても 1 つ行きすぎない")
    void tableDoesNotOvershoot() {
        assertThat(tableService.placeTableNextTo(tblA, tblC, null)).isTrue();
        assertThat(tblOrder()).containsExactly("卓B", "卓A", "卓C");
    }

    @Test
    @DisplayName("★ 卓：知らない相手なら何もしない")
    void tableIgnoresUnknownTarget() {
        assertThat(tableService.placeTableNextTo(tblA, 999999L, null)).isFalse();
        assertThat(tblOrder()).containsExactly("卓A", "卓B", "卓C");
    }

    @Test
    @DisplayName("★ 卓：動かす必要が無いときは false")
    void tableReportsWhenNothingMoved() {
        assertThat(tableService.placeTableNextTo(tblA, tblB, null))
                .as("卓A はもともと 卓B の直前。動かす必要が無い")
                .isFalse();
        assertThat(tblOrder()).containsExactly("卓A", "卓B", "卓C");
    }
}
