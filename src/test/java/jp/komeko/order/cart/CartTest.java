package jp.komeko.order.cart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Cart}（買い物カゴ）のテスト。
 *
 * <p><b>このテストが守っているもの＝「お客さんが見る画面の分かりやすさ」と「サーバの安全」</b>
 * <ul>
 *   <li>同じ商品を続けて入れたのにカートが 2 行に分かれると「壊れている」と思われる</li>
 *   <li>逆に、トッピングが違うのに 1 行にまとまってしまうと厨房が間違った品を作る</li>
 *   <li>上限が効いていないと、いたずらで数万個入れられてサーバのメモリを食い潰される</li>
 * </ul>
 *
 * <p><b>{@code @SessionScope} が付いていても素の new でテストできる</b><br>
 * Cart は Spring の Bean ですが、中身はただの Java オブジェクトで、
 * DB もセッションも参照していません。
 * そのため {@code new Cart()} でそのままテストできます。
 * 「Bean だから Spring を起動しないとテストできない」は思い込みで、
 * <b>依存を持たないクラスは Spring 抜きでテストするのが速くて確実</b>です。
 */
@DisplayName("カート（買い物カゴ）")
class CartTest {

    // ── テスト用の部品を短く書くためのヘルパー ──────────────────

    /** 画像なしのカート行を作る。 */
    private CartLine line(Long menuItemId, String name, int price, int cookMinutes,
                          List<CartOption> options, int quantity) {
        return new CartLine(menuItemId, name, null, price, cookMinutes, options, quantity);
    }

    private CartLine galette(int quantity, CartOption... options) {
        return line(1L, "コンプレット", 880, 8, List.of(options), quantity);
    }

    private CartLine coffee(int quantity) {
        return line(2L, "ドリップコーヒー", 400, 2, List.of(), quantity);
    }

    private static final CartOption CHEESE = new CartOption(10L, "トッピング", "チーズ増量", 150);
    private static final CartOption EGG = new CartOption(20L, "トッピング", "目玉焼き追加", 120);

    @Nested
    @DisplayName("同じ内容をまとめる")
    class Merging {

        @Test
        @DisplayName("同じ内容を2回入れると1行にまとまって個数が増える")
        void mergesIdenticalLines() {
            Cart cart = new Cart();

            cart.add(galette(1));
            cart.add(galette(2));

            assertThat(cart.getLines()).hasSize(1);
            assertThat(cart.getLines().get(0).getQuantity()).isEqualTo(3);
            assertThat(cart.getTotalQuantity()).isEqualTo(3);
        }

        @Test
        @DisplayName("オプションを選んだ順番が違っても同じ行にまとまる")
        void optionOrderDoesNotMatter() {
            // 画面のチェックボックスは押した順にサーバへ届くので、
            // 「チーズ→卵」と「卵→チーズ」は同じ内容なのに順番だけが違う。
            // キーを作るときに選択肢 ID を昇順に並べ替えているのはこのため。
            Cart cart = new Cart();

            cart.add(galette(1, CHEESE, EGG));
            cart.add(galette(1, EGG, CHEESE));

            assertThat(cart.getLines()).hasSize(1);
            assertThat(cart.getLines().get(0).getQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("キーは 商品ID + 選択肢IDの昇順 で作られる")
        void keyIsStable() {
            // CartLine のキー生成そのものを直接確かめる。
            // まとまる／まとまらないの根拠がここ 1 か所に集約されている。
            assertThat(galette(1, CHEESE, EGG).getKey())
                    .isEqualTo(galette(1, EGG, CHEESE).getKey());

            // オプション無しと有りは別物
            assertThat(galette(1).getKey()).isNotEqualTo(galette(1, CHEESE).getKey());
            // 商品が違えば当然別物
            assertThat(galette(1).getKey()).isNotEqualTo(coffee(1).getKey());
        }

        @Test
        @DisplayName("オプションの中身が違えば別の行になる（厨房が作り間違えないため）")
        void differentOptionsMakeDifferentLines() {
            Cart cart = new Cart();

            cart.add(galette(1, CHEESE));
            cart.add(galette(1, EGG));
            cart.add(galette(1));

            assertThat(cart.getLines()).hasSize(3);
            assertThat(cart.getTotalQuantity()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("個数の変更と削除")
    class QuantityAndRemoval {

        @Test
        @DisplayName("個数0で更新すると行ごと消える")
        void zeroQuantityRemovesLine() {
            // 「−」ボタンを押し続けると 0 になる。
            // そこで 0 個の行が残ると、お客さんは削除の仕方が分からなくなる。
            Cart cart = new Cart();
            CartLine added = cart.add(galette(2));

            cart.changeQuantity(added.getKey(), 0);

            assertThat(cart.getLines()).isEmpty();
            assertThat(cart.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("マイナスの個数で更新しても行ごと消える（負の個数は作らせない）")
        void negativeQuantityRemovesLine() {
            Cart cart = new Cart();
            CartLine added = cart.add(galette(2));

            cart.changeQuantity(added.getKey(), -5);

            assertThat(cart.getLines()).isEmpty();
        }

        @Test
        @DisplayName("存在しないキーで更新・削除しても何も起きない（エラーにしない）")
        void unknownKeyIsIgnored() {
            // 別のタブで削除したあとに古い画面から操作されることがある。
            // 例外にするとエラー画面が出てしまうので、黙って無視するのが正しい。
            Cart cart = new Cart();
            cart.add(galette(1));

            cart.changeQuantity("存在しないキー", 5);
            cart.remove("存在しないキー");

            assertThat(cart.getLines()).hasSize(1);
            assertThat(cart.getLines().get(0).getQuantity()).isEqualTo(1);
        }

        @Test
        @DisplayName("clear() でカートも入力内容も空になる")
        void clearResetsEverything() {
            Cart cart = new Cart();
            cart.add(galette(1));
            cart.setCustomerName("たなか");
            cart.setNote("ソース少なめ");

            cart.clear();

            assertThat(cart.isEmpty()).isTrue();
            assertThat(cart.getCustomerName()).isNull();
            assertThat(cart.getNote()).isNull();
        }

        @Test
        @DisplayName("getLines() は書き換えできないリストを返す（外から勝手に追加させない）")
        void linesAreUnmodifiable() {
            // 直接 add されると上限チェックを素通りしてしまう。
            // 変更は必ず Cart のメソッド経由にする、という約束をコードで守っている。
            Cart cart = new Cart();
            cart.add(galette(1));

            assertThatThrownBy(() -> cart.getLines().add(coffee(1)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("上限のチェック")
    class Limits {

        @Test
        @DisplayName("1行あたりの個数は MAX_QUANTITY_PER_LINE を超えない")
        void quantityIsCapped() {
            Cart cart = new Cart();

            // 上限より多い個数で入れても、上限で頭打ちになる
            CartLine added = cart.add(galette(Cart.MAX_QUANTITY_PER_LINE + 5));
            assertThat(added.getQuantity()).isEqualTo(Cart.MAX_QUANTITY_PER_LINE);

            // 追加でさらに足しても上限を超えない
            cart.add(galette(10));
            assertThat(cart.getLines().get(0).getQuantity()).isEqualTo(Cart.MAX_QUANTITY_PER_LINE);

            // 個数変更でも同じく頭打ちになる
            cart.changeQuantity(added.getKey(), 999);
            assertThat(cart.getLines().get(0).getQuantity()).isEqualTo(Cart.MAX_QUANTITY_PER_LINE);
        }

        @Test
        @DisplayName("行数が MAX_LINES に達したら、それ以上追加できず例外になる")
        void lineCountIsCapped() {
            Cart cart = new Cart();

            // 上限ちょうどまでは入る（商品 ID を変えて別の行にする）
            for (int i = 1; i <= Cart.MAX_LINES; i++) {
                cart.add(line((long) i, "商品" + i, 500, 5, List.of(), 1));
            }
            assertThat(cart.getLines()).hasSize(Cart.MAX_LINES);

            // 上限を 1 つ超えると拒否される。
            // 個数と違って「黙って切り捨て」ではなく例外にしているのは、
            // お客さんに「これ以上入りません」と伝える必要があるため。
            assertThatThrownBy(() -> cart.add(line(9999L, "あふれる商品", 500, 5, List.of(), 1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(String.valueOf(Cart.MAX_LINES));

            assertThat(cart.getLines()).hasSize(Cart.MAX_LINES);
        }

        @Test
        @DisplayName("行数が上限でも、すでにある行への個数追加はできる")
        void mergingStillWorksAtLineLimit() {
            // 上限チェックは「新しい行を作るとき」だけ。
            // 既存の行に足すだけならメモリは増えないので通してよい。
            Cart cart = new Cart();
            for (int i = 1; i <= Cart.MAX_LINES; i++) {
                cart.add(line((long) i, "商品" + i, 500, 5, List.of(), 1));
            }

            cart.add(line(1L, "商品1", 500, 5, List.of(), 3));

            assertThat(cart.getLines()).hasSize(Cart.MAX_LINES);
            assertThat(cart.getLines().get(0).getQuantity()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("合計の計算")
    class Totals {

        @Test
        @DisplayName("合計金額・合計点数・調理見込みが正しく出る")
        void calculatesTotals() {
            Cart cart = new Cart();

            // コンプレット 880円 + チーズ増量 150円 = 1030円 × 2個（1個8分）
            cart.add(galette(2, CHEESE));
            // ドリップコーヒー 400円 × 1個（1個2分）
            cart.add(coffee(1));

            assertThat(cart.getTotalAmount()).isEqualTo(2460);      // 1030×2 + 400
            assertThat(cart.getTotalQuantity()).isEqualTo(3);
            assertThat(cart.getTotalCookMinutes()).isEqualTo(18);   // 8×2 + 2×1
        }

        @Test
        @DisplayName("オプション代は単価に足されてから個数を掛ける")
        void optionPriceIsPerUnit() {
            // 「オプション代を1回だけ足す」実装にすると、
            // 2個頼んだときにトッピング1個分しか請求できず売上が減る。
            CartLine withOptions = galette(2, CHEESE, EGG);

            assertThat(withOptions.getUnitPrice()).isEqualTo(880 + 150 + 120);
            assertThat(withOptions.getSubtotal()).isEqualTo((880 + 150 + 120) * 2);
            assertThat(withOptions.getOptionSummary()).isEqualTo("チーズ増量 / 目玉焼き追加");
        }

        @Test
        @DisplayName("空のカートの合計は 0")
        void emptyCart() {
            Cart cart = new Cart();

            assertThat(cart.isEmpty()).isTrue();
            assertThat(cart.getTotalAmount()).isZero();
            assertThat(cart.getTotalQuantity()).isZero();
            assertThat(cart.getTotalCookMinutes()).isZero();
        }

        @Test
        @DisplayName("replaceAll() で中身を丸ごと入れ替えられる（注文直前の価格洗い替え用）")
        void replaceAll() {
            // カートはセッションに残り続けるので、注文直前に最新のメニュー情報で
            // 作り直す必要がある。その差し替え口が replaceAll。
            Cart cart = new Cart();
            cart.add(galette(2));

            cart.replaceAll(List.of(coffee(1)));

            assertThat(cart.getLines()).hasSize(1);
            assertThat(cart.getTotalAmount()).isEqualTo(400);
        }
    }
}
