package jp.komeko.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrderStatus} の状態遷移ルールのテスト。
 *
 * <p><b>このテストが守っているもの＝「伝票の整合性」</b><br>
 * 厨房画面のボタンだけで状態を制御していると、
 * ブラウザの「戻る」や URL の直接入力で、ありえない状態変更ができてしまいます。
 * 例えば「受渡済（会計まで終わった伝票）」を「調理中」に戻せてしまうと、
 * 売上集計が二重になったり、渡したはずの商品をもう一度焼いてしまいます。
 *
 * <p>そこで <b>遷移してよい／いけない</b> のルールを enum 自身に持たせ、
 * 画面とサーバの両方がこの 1 か所を参照するようにしています。
 * ここが壊れると全部が壊れるので、テストで固定しておきます。
 *
 * <p><b>{@code @EnumSource} の便利さ</b><br>
 * enum の全定数を 1 つずつ流し込んでテストしてくれます。
 * 将来「返金済」のような状態を足したときも、
 * このテストが自動的に新しい定数までチェックしてくれます。
 */
@DisplayName("注文ステータスの遷移ルール")
class OrderStatusTest {

    @Nested
    @DisplayName("進めてよい遷移")
    class AllowedTransitions {

        @Test
        @DisplayName("RECEIVED（受付）からは 調理中・お渡し可・キャンセル へ進める")
        void fromReceived() {
            // ドリンクだけの注文など、焼く工程が無いものは
            // 受付からいきなり「お渡し可」にできると現場が速い。
            assertThat(OrderStatus.RECEIVED.canTransitionTo(OrderStatus.COOKING)).isTrue();
            assertThat(OrderStatus.RECEIVED.canTransitionTo(OrderStatus.READY)).isTrue();
            assertThat(OrderStatus.RECEIVED.canTransitionTo(OrderStatus.CANCELED)).isTrue();

            assertThat(OrderStatus.RECEIVED.allowedNext())
                    .containsExactlyInAnyOrder(
                            OrderStatus.COOKING, OrderStatus.READY, OrderStatus.CANCELED);
        }

        @Test
        @DisplayName("COOKING（調理中）からは お渡し可・キャンセル へ進める")
        void fromCooking() {
            assertThat(OrderStatus.COOKING.canTransitionTo(OrderStatus.READY)).isTrue();
            assertThat(OrderStatus.COOKING.canTransitionTo(OrderStatus.CANCELED)).isTrue();

            assertThat(OrderStatus.COOKING.allowedNext())
                    .containsExactlyInAnyOrder(OrderStatus.READY, OrderStatus.CANCELED);
        }

        @Test
        @DisplayName("READY（お渡し可）からは 受渡済 へ。焼き直しのため調理中へ戻すのだけは許す")
        void fromReady() {
            assertThat(OrderStatus.READY.canTransitionTo(OrderStatus.COMPLETED)).isTrue();
            assertThat(OrderStatus.READY.canTransitionTo(OrderStatus.CANCELED)).isTrue();
            // 「呼んだけどお客さんが来ない → 冷めたので焼き直す」が現場では実際に起きる。
            // ここだけは意図的に巻き戻しを許可している（仕様であってバグではない）。
            assertThat(OrderStatus.READY.canTransitionTo(OrderStatus.COOKING)).isTrue();
        }
    }

    @Nested
    @DisplayName("禁じている遷移")
    class ForbiddenTransitions {

        @ParameterizedTest(name = "COMPLETED → {0} はできない")
        @EnumSource(OrderStatus.class)
        void fromCompletedNothingIsAllowed(OrderStatus next) {
            // 会計まで終わった伝票は「閉じた」扱い。何があっても動かさない。
            // 訂正が必要なら、状態を戻すのではなく返金として別に記録するのが会計上正しい。
            assertThat(OrderStatus.COMPLETED.canTransitionTo(next)).isFalse();
            assertThat(OrderStatus.COMPLETED.allowedNext()).isEmpty();
        }

        @ParameterizedTest(name = "CANCELED → {0} はできない")
        @EnumSource(OrderStatus.class)
        void fromCanceledNothingIsAllowed(OrderStatus next) {
            assertThat(OrderStatus.CANCELED.canTransitionTo(next)).isFalse();
            assertThat(OrderStatus.CANCELED.allowedNext()).isEmpty();
        }

        @Test
        @DisplayName("COOKING → RECEIVED のような巻き戻しはできない")
        void cannotRewindToReceived() {
            // 一度鉄板に乗せたものを「まだ受付」に戻すと、
            // 待ち時間の計算（前に並んでいる組数）が実態と合わなくなる。
            assertThat(OrderStatus.COOKING.canTransitionTo(OrderStatus.RECEIVED)).isFalse();
            assertThat(OrderStatus.READY.canTransitionTo(OrderStatus.RECEIVED)).isFalse();
            // 焼き上がりを確認せずに受渡済へ飛ばすこともできない（渡し忘れ防止）
            assertThat(OrderStatus.COOKING.canTransitionTo(OrderStatus.COMPLETED)).isFalse();
            assertThat(OrderStatus.RECEIVED.canTransitionTo(OrderStatus.COMPLETED)).isFalse();
        }

        @ParameterizedTest(name = "{0} → 自分自身 は遷移先の一覧に含まれない")
        @EnumSource(OrderStatus.class)
        void selfTransitionIsNotInTheTable(OrderStatus status) {
            // 「二度押ししても大丈夫」という配慮は Order#changeStatus 側で行っている。
            // ルール表（allowedNext）のほうには自分自身を入れない、という役割分担。
            assertThat(status.allowedNext()).doesNotContain(status);
            assertThat(status.canTransitionTo(status)).isFalse();
        }
    }

    @Nested
    @DisplayName("標準の次の一手（primaryNext）")
    class PrimaryNext {

        /**
         * このテストが守っているもの＝<b>厨房ボードのボタンの強弱</b>。
         *
         * <p>受付レーンには「焼きはじめ（→調理中）」と「焼き上がり（→お渡し可）」が並びます。
         * 後者はドリンクだけの注文で焼く工程を飛ばすための操作です。
         * ここが入れ替わると、忙しい厨房で一番目立つボタンが飛ばし技になり、
         * 「押したら焼かずにお渡し可になっていた」が起きます。
         */
        @Test
        @DisplayName("受付の標準の次は「調理中」。お渡し可は飛ばし技なので標準ではない")
        void fromReceived() {
            assertThat(OrderStatus.RECEIVED.primaryNext()).contains(OrderStatus.COOKING);
            assertThat(OrderStatus.RECEIVED.isPrimaryNext(OrderStatus.COOKING)).isTrue();
            assertThat(OrderStatus.RECEIVED.isPrimaryNext(OrderStatus.READY)).isFalse();
        }

        @Test
        @DisplayName("調理中の標準の次は「お渡し可」")
        void fromCooking() {
            assertThat(OrderStatus.COOKING.primaryNext()).contains(OrderStatus.READY);
            assertThat(OrderStatus.COOKING.isPrimaryNext(OrderStatus.READY)).isTrue();
        }

        @Test
        @DisplayName("お渡し可の標準の次は「受渡済」。調理中へ戻すのは一段戻す操作なので標準ではない")
        void fromReady() {
            // 同じ COOKING でも、受付から見れば前へ進める操作、
            // お渡し可から見れば焼き直しのために戻す操作。
            // 「遷移先の名前」だけを見ていると、この違いが消える。
            assertThat(OrderStatus.READY.primaryNext()).contains(OrderStatus.COMPLETED);
            assertThat(OrderStatus.READY.isPrimaryNext(OrderStatus.COMPLETED)).isTrue();
            assertThat(OrderStatus.READY.isPrimaryNext(OrderStatus.COOKING)).isFalse();
        }

        @ParameterizedTest(name = "{0} からは標準の次の一手が無い")
        @EnumSource(value = OrderStatus.class, names = {"COMPLETED", "CANCELED"})
        void terminalStatesHaveNoPrimaryNext(OrderStatus status) {
            assertThat(status.primaryNext()).isEmpty();
            // 空のときに isPrimaryNext が例外を投げたり true を返したりしないこと。
            // 閉じた伝票のチケットにボタンは出ないが、判定側が壊れないのは別途保証する。
            assertThat(status.isPrimaryNext(OrderStatus.COOKING)).isFalse();
        }

        @ParameterizedTest(name = "{0} の標準の次の一手はキャンセルではない")
        @EnumSource(OrderStatus.class)
        void cancelIsNeverThePrimaryNext(OrderStatus status) {
            // キャンセルはどの状態からでも選べる「横道」で、進むべき道ではない。
            // ボードでも一段下げた場所に別枠で置いている。
            assertThat(status.isPrimaryNext(OrderStatus.CANCELED)).isFalse();
        }

        @ParameterizedTest(name = "{0} の標準の次の一手は、遷移してよい先の一つである")
        @EnumSource(OrderStatus.class)
        void primaryNextIsAlwaysAllowed(OrderStatus status) {
            // 「画面に出したボタンをサーバが弾く」を構造的に起こさないための保険。
            status.primaryNext().ifPresent(next ->
                    assertThat(status.canTransitionTo(next)).isTrue());
        }
    }

    @Nested
    @DisplayName("状態の性質を判定するメソッド")
    class StateFlags {

        @Test
        @DisplayName("isActive() は 受付・調理中 のときだけ true（厨房に仕事が残っている状態）")
        void isActive() {
            // 待ち時間の目安は「前に並んでいるアクティブな注文」から計算する。
            // ここを間違えると、お客さんへの待ち時間案内がまるごとずれる。
            assertThat(OrderStatus.RECEIVED.isActive()).isTrue();
            assertThat(OrderStatus.COOKING.isActive()).isTrue();

            // お渡し可はもう焼き上がっているので「厨房の仕事」としては終わっている
            assertThat(OrderStatus.READY.isActive()).isFalse();
            assertThat(OrderStatus.COMPLETED.isActive()).isFalse();
            assertThat(OrderStatus.CANCELED.isActive()).isFalse();
        }

        @Test
        @DisplayName("isClosed() は 受渡済・キャンセル のときだけ true（伝票が閉じた状態）")
        void isClosed() {
            assertThat(OrderStatus.COMPLETED.isClosed()).isTrue();
            assertThat(OrderStatus.CANCELED.isClosed()).isTrue();

            assertThat(OrderStatus.RECEIVED.isClosed()).isFalse();
            assertThat(OrderStatus.COOKING.isClosed()).isFalse();
            assertThat(OrderStatus.READY.isClosed()).isFalse();
        }

        @ParameterizedTest(name = "{0} は isActive と isClosed が同時に true にならない")
        @EnumSource(OrderStatus.class)
        void activeAndClosedAreExclusive(OrderStatus status) {
            // 「厨房で作業中なのに伝票は閉じている」という矛盾を作らないための保険。
            assertThat(status.isActive() && status.isClosed()).isFalse();
        }

        @ParameterizedTest(name = "{0} には画面表示用のラベルと色が設定されている")
        @EnumSource(OrderStatus.class)
        void displayValuesArePresent(OrderStatus status) {
            // 画面に空文字や null が出ると、お客さんには「壊れている」ように見える。
            // enum に定数を足したときのラベル付け忘れをここで検出する。
            assertThat(status.getStaffLabel()).isNotBlank();
            assertThat(status.getCustomerLabel()).isNotBlank();
            assertThat(status.getColor()).startsWith("#");
        }
    }
}
