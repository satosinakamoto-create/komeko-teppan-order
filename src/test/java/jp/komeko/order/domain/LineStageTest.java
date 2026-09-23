package jp.komeko.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 品ごとの段階（2026-09-23 追加）。
 *
 * <p><b>なぜ注文の状態と別に要るのか。</b>
 * {@link OrderStatus} は<b>注文</b>（カートを一度に確定した単位）の状態なので、
 * 4 品まとめて頼まれると 1 件になり、「たこ焼だけ焼き上がった」を表せません。
 * 店主の指摘がそのまま理由です——
 * <pre>
 *   「一気に注文入ったら一気にその卓の料理しないといけなくなるわけじゃん。
 *     でも調理は一つずつだから、どうやって提供可能のボタン分けたら良いか」
 * </pre>
 *
 * <p><b>このテストが守っているのは、押し間違いから戻れることです。</b>
 * 厨房ボードは営業中に片手で操作します。必ず押し間違えます。
 * 戻れない操作は「提供済み」だけにしてあり、そこからの訂正は
 * 段階を巻き戻すのではなく取り消し（{@code OrderLine#cancel}）の仕事です。
 * 卓の上に出してしまった料理は、段階を戻しても戻りません。
 */
@DisplayName("品ごとの段階")
class LineStageTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 23);

    private OrderLine newLine() {
        Order order = new Order(BUSINESS_DATE, 101, 10);
        order.addLine(new OrderLine(1L, "国産砂肝の鉄板塩たれ焼", 880, 1, 8));
        order.recalculate();
        return order.getLines().get(0);
    }

    @Nested
    @DisplayName("進む・戻る")
    class Moving {

        @Test
        @DisplayName("新しい品は未調理から始まる")
        void startsUncooked() {
            assertThat(newLine().getStage()).isEqualTo(LineStage.UNCOOKED);
        }

        @Test
        @DisplayName("未調理 → 調理済み → 提供済み と進める")
        void goesForward() {
            OrderLine line = newLine();
            line.moveTo(LineStage.COOKED);
            assertThat(line.getStage()).isEqualTo(LineStage.COOKED);
            line.moveTo(LineStage.SERVED);
            assertThat(line.getStage()).isEqualTo(LineStage.SERVED);
        }

        /**
         * ★ 押し間違いから戻れること。
         *
         * <p>店主：「調理済み提供可能ボタンを間違って押したとき、
         * 一個前の状態に戻すボタンが必要なんじゃない？」
         */
        @Test
        @DisplayName("★ 調理済みから未調理へ戻せる（押し間違いの救済）")
        void canStepBackFromCooked() {
            OrderLine line = newLine();
            line.moveTo(LineStage.COOKED);
            line.moveTo(LineStage.UNCOOKED);
            assertThat(line.getStage()).isEqualTo(LineStage.UNCOOKED);
        }

        /**
         * ★★ 提供済みからは戻せないこと。
         *
         * <p>卓の上に出してしまった料理は、段階を巻き戻しても戻りません。
         * ここを開けると「出したのに未調理」という、厨房から見て
         * 説明のつかない行ができます。提供後の訂正は取り消しの仕事です。
         */
        @Test
        @DisplayName("★★ 提供済みからは戻せない（出した料理は戻らない）")
        void cannotStepBackFromServed() {
            OrderLine line = newLine();
            line.moveTo(LineStage.COOKED);
            line.moveTo(LineStage.SERVED);

            assertThat(LineStage.SERVED.allowedNext()).isEmpty();
            assertThatThrownBy(() -> line.moveTo(LineStage.COOKED))
                    .isInstanceOf(IllegalStateException.class);
        }

        /** 未調理から一気に提供済みへは飛べない（焼かずに出すことになる）。 */
        @Test
        @DisplayName("未調理から提供済みへは飛べない")
        void cannotSkipCooking() {
            assertThatThrownBy(() -> newLine().moveTo(LineStage.SERVED))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("時刻の記録")
    class Timestamps {

        /**
         * ★★ 調理済みの時刻は、調理済みレーンの<b>並び順</b>を決めます。
         *
         * <p>注文からの経過時間ではありません。左のレーンは「何分待たせたか」、
         * 右は「焼き上がってから何分置いたか」で、見ている時計が違います。
         */
        @Test
        @DisplayName("★ 調理済みにすると時刻が入る（並び順の根拠）")
        void recordsCookedAt() {
            OrderLine line = newLine();
            assertThat(line.getCookedAt()).isNull();
            line.moveTo(LineStage.COOKED);
            assertThat(line.getCookedAt()).isNotNull();
        }

        /**
         * ★★ 戻して進め直しても、最初の時刻を残すこと。
         *
         * <p>上書きすると、押し間違いを直しただけで札が列の最後尾へ飛びます。
         * 直した人が「自分のせいで順番が変わった」と気づけません。
         */
        @Test
        @DisplayName("★★ 戻して進め直しても、最初に焼けた時刻のまま（札が列の最後尾へ飛ばない）")
        void keepsTheFirstCookedAt() {
            OrderLine line = newLine();
            line.moveTo(LineStage.COOKED);
            var first = line.getCookedAt();

            line.moveTo(LineStage.UNCOOKED);
            line.moveTo(LineStage.COOKED);

            assertThat(line.getCookedAt())
                    .as("★ 時刻を上書きすると、直した品が列の最後尾へ移動する")
                    .isEqualTo(first);
        }
    }

    @Nested
    @DisplayName("取り消しとの関係")
    class WithCancel {

        /**
         * ★ 取り消した品は動かせないこと。
         *
         * <p>取り消した品は厨房ボードに出ないので、正規の操作では届きません。
         * 通すと「取り消したのに提供済み」という行ができます。
         */
        @Test
        @DisplayName("★ 取り消した品は段階を動かせない")
        void canceledLinesDoNotMove() {
            OrderLine line = newLine();
            line.cancel("お客さま都合", false);

            assertThatThrownBy(() -> line.moveTo(LineStage.COOKED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("取り消した品");
        }

        /**
         * 段階と取り消しは別の軸であること。
         *
         * <p>「提供済みで、かつ取り消し」は実際に起きます——
         * お客さまに出したあとで「これ違います」と言われる場面です。
         * 1 本の軸にまとめると、これが表せなくなります。
         */
        @Test
        @DisplayName("提供済みの品も取り消せる（出したあとの「これ違います」）")
        void servedLinesCanStillBeCanceled() {
            OrderLine line = newLine();
            line.moveTo(LineStage.COOKED);
            line.moveTo(LineStage.SERVED);

            assertThat(line.cancel("提供後の訂正", false)).isTrue();
            assertThat(line.getStage()).isEqualTo(LineStage.SERVED);
            assertThat(line.isCanceled()).isTrue();
        }
    }

    @Nested
    @DisplayName("厨房ボードに出すか")
    class OnBoard {

        @Test
        @DisplayName("未調理と調理済みは出す。提供済みと取り消しは消える")
        void decidesWhatStaysOnTheBoard() {
            OrderLine uncooked = newLine();
            assertThat(uncooked.isOnKitchenBoard()).isTrue();

            OrderLine cooked = newLine();
            cooked.moveTo(LineStage.COOKED);
            assertThat(cooked.isOnKitchenBoard()).isTrue();

            OrderLine served = newLine();
            served.moveTo(LineStage.COOKED);
            served.moveTo(LineStage.SERVED);
            assertThat(served.isOnKitchenBoard()).isFalse();

            OrderLine canceled = newLine();
            canceled.cancel("廃棄", false);
            assertThat(canceled.isOnKitchenBoard()).isFalse();
        }
    }
}
