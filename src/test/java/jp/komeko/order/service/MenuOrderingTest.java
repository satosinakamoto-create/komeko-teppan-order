package jp.komeko.order.service;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.MenuItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * メニューの並び順（{@link MenuService} の上下移動と自動採番）のテスト。
 *
 * <p><b>このテストが守っているもの＝「押したのに動かない」を起こさないこと</b><br>
 * 並び替えは、失敗しても例外が出ません。押しても何も起きないだけです。
 * だから壊れても気づきにくく、気づいたときには
 * 「このアプリの並び替えは効かない」と思われています。
 *
 * <p>実際に踏みやすい落とし穴が 2 つあります。
 * <ul>
 *   <li><b>数字を 1 増減させる実装</b>。隣が 10 離れていれば順番は変わりません。</li>
 *   <li><b>同じ数字が並んでいる場合</b>。交換しても両方同じ値のままで、
 *       id 順の並びが動きません。開店時に流し込んだデータでよく起きます
 *       （既定値の 0 が並ぶ）。</li>
 * </ul>
 * どちらも「隣と席を交換する」だけでは足りず、
 * 同値のときにずらす処理が要ります。そこをここで固めています。
 *
 * <p>自動採番のほうは、もっと実害が分かりやすい失敗です。
 * 既定値の 0 のまま保存すると、追加した品が<b>看板メニューの上に割り込みます</b>。
 * 店主が気づいて直すまで、お客さまにはその並びで見えています。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("メニューの並び順")
class MenuOrderingTest {

    @Autowired
    private MenuService menuService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private MenuItemRepository menuItemRepository;

    private Category kitchen;

    @BeforeEach
    void setUp() {
        // 開発用の仕込みデータが入っている環境でも結果が変わらないよう、
        // このテストが作ったカテゴリの中だけを見る。
        kitchen = categoryRepository.save(new Category("鉄板おつまみ", 100));
    }

    private MenuItem addItem(String name, int sortOrder) {
        MenuItem item = new MenuItem(kitchen, name, 500);
        item.setSortOrder(sortOrder);
        return menuItemRepository.save(item);
    }

    /** そのカテゴリの商品を、画面に出るのと同じ順で並べた名前の一覧。 */
    private List<String> itemNames() {
        return menuItemRepository.findByCategoryIdOrderBySortOrderAscIdAsc(kitchen.getId())
                .stream().map(MenuItem::getName).toList();
    }

    @Nested
    @DisplayName("上下の移動")
    class Move {

        @Test
        @DisplayName("ひとつ上へ動かすと、その 1 つだけ順番が入れ替わる")
        void moveUpSwapsWithNeighbour() {
            addItem("砂肝", 10);
            MenuItem beef = addItem("牛すじ", 20);
            addItem("ねぎ焼", 30);

            menuService.moveItem(beef.getId(), true);

            assertThat(itemNames()).containsExactly("牛すじ", "砂肝", "ねぎ焼");
        }

        @Test
        @DisplayName("ひとつ下へ動かせる")
        void moveDownSwapsWithNeighbour() {
            MenuItem gizzard = addItem("砂肝", 10);
            addItem("牛すじ", 20);
            addItem("ねぎ焼", 30);

            menuService.moveItem(gizzard.getId(), false);

            assertThat(itemNames()).containsExactly("牛すじ", "砂肝", "ねぎ焼");
        }

        @Test
        @DisplayName("並び順が全部同じでも動く（開店時のデータは 0 が並んでいる）")
        void movesEvenWhenSortOrdersAreIdentical() {
            // 数字を交換するだけの実装だと、両方 0 のままで順番が変わらない。
            // 押しても何も起きないので、壊れていることに気づけない。
            addItem("砂肝", 0);
            MenuItem beef = addItem("牛すじ", 0);
            addItem("ねぎ焼", 0);

            menuService.moveItem(beef.getId(), true);

            assertThat(itemNames()).containsExactly("牛すじ", "砂肝", "ねぎ焼");
        }

        @Test
        @DisplayName("端では動かない（false が返る）")
        void cannotMoveBeyondTheEdge() {
            MenuItem first = addItem("砂肝", 10);
            MenuItem last = addItem("牛すじ", 20);

            assertThat(menuService.moveItem(first.getId(), true)).isFalse();
            assertThat(menuService.moveItem(last.getId(), false)).isFalse();
            assertThat(itemNames()).containsExactly("砂肝", "牛すじ");
        }

        @Test
        @DisplayName("カテゴリをまたいでは動かない（並べ替えで所属が変わらない）")
        void neverCrossesCategories() {
            // 上下ボタンでカテゴリが変わってしまうと、
            // 並べ替えたつもりが商品の所属を書き換えたことになる。
            Category drinks = categoryRepository.save(new Category("お飲み物", 200));
            MenuItem beer = new MenuItem(drinks, "生ビール", 600);
            beer.setSortOrder(10);
            menuItemRepository.save(beer);

            MenuItem top = addItem("砂肝", 10);

            assertThat(menuService.moveItem(top.getId(), true)).isFalse();
            assertThat(menuItemRepository.findById(top.getId()).orElseThrow()
                    .getCategory().getId()).isEqualTo(kitchen.getId());
        }

        @Test
        @DisplayName("カテゴリも同じように上下へ動かせる")
        void categoriesMoveToo() {
            Category drinks = categoryRepository.save(new Category("お飲み物", 200));

            menuService.moveCategory(drinks.getId(), true);

            // 「鉄板おつまみ(100)」より前に来ていること。
            // 仕込みデータの有無で前後の件数が変わるので、この 2 つの関係だけを見る。
            List<Long> ids = categoryRepository.findAllByOrderBySortOrderAscIdAsc()
                    .stream().map(Category::getId).toList();
            assertThat(ids.indexOf(drinks.getId())).isLessThan(ids.indexOf(kitchen.getId()));
        }
    }

    @Nested
    @DisplayName("新しく足したものの並び順")
    class NextSortOrder {

        @Test
        @DisplayName("新しい商品はそのカテゴリの末尾に付く（先頭に割り込まない）")
        void newItemGoesToTheEnd() {
            addItem("砂肝", 10);
            addItem("牛すじ", 20);

            assertThat(menuService.nextItemSortOrder(kitchen.getId())).isEqualTo(30);
        }

        @Test
        @DisplayName("最初の 1 品でも 0 にはしない（あとで手前に入れられるように空けておく）")
        void firstItemStartsAtTen() {
            assertThat(menuService.nextItemSortOrder(kitchen.getId())).isEqualTo(10);
        }

        @Test
        @DisplayName("新しいカテゴリも末尾に付く")
        void newCategoryGoesToTheEnd() {
            categoryRepository.save(new Category("お飲み物", 200));

            assertThat(menuService.nextCategorySortOrder()).isEqualTo(210);
        }
    }
}
