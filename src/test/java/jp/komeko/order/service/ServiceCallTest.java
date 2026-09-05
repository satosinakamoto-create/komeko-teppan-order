package jp.komeko.order.service;

import jp.komeko.order.domain.ServiceCall;
import jp.komeko.order.domain.ServiceCallType;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.TableSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * お客さまからの呼び出しの決めごとを固定する。
 *
 * <p>Spring を起動しない素の JUnit。守りたいのは
 * 「対応済みを上書きしない」「対応までの分数を数える」という
 * {@link ServiceCall} 自身の判断だけなので、DB は要らない。
 *
 * <p><b>このテストが守っているもの</b><br>
 * ホール画面は数秒ごとに描き直されるので、二人が同じ呼び出しに
 * 同時に「対応した」を押すことが普通に起こる。
 * あとから押した人で上書きすると、先に向かった人の記録が消える。
 * 記録が消えると「誰が対応したか」が追えなくなり、
 * 呼ばれてから何分かかったかの集計も狂う。
 */
@DisplayName("お客さまからの呼び出し")
class ServiceCallTest {

    /** 伝票を 1 つ作る。金額の計算には触らないので、最小限の設定でよい。 */
    private TableSession session() {
        ShopSetting setting = new ShopSetting();
        DiningTable table = new DiningTable("カウンター1", 1, 10);
        return new TableSession(table, java.time.LocalDate.now(), 2, setting);
    }

    @Nested
    @DisplayName("対応の記録")
    class Handling {

        @Test
        @DisplayName("作った直後は未対応")
        void newCallIsPending() {
            ServiceCall call = new ServiceCall(session(), ServiceCallType.STAFF);

            assertThat(call.isPending()).isTrue();
            assertThat(call.getHandledAt()).isNull();
            assertThat(call.getHandledBy()).isNull();
        }

        @Test
        @DisplayName("対応すると、担当者と時刻が残る")
        void handlingRecordsWhoAndWhen() {
            ServiceCall call = new ServiceCall(session(), ServiceCallType.CHECKOUT);

            call.handle("ホールスタッフ");

            assertThat(call.isPending()).isFalse();
            assertThat(call.getHandledBy()).isEqualTo("ホールスタッフ");
            assertThat(call.getHandledAt()).isNotNull();
        }

        @Test
        @DisplayName("★ 二人目が押しても、先に対応した人の記録は消えない")
        void secondHandlerDoesNotOverwrite() {
            // ホール画面は数秒ごとに描き直されるので、同じ呼び出しが
            // 二人の画面に出ている時間がある。両方が押すのは異常ではなく日常。
            ServiceCall call = new ServiceCall(session(), ServiceCallType.STAFF);

            call.handle("先に向かった人");
            var firstTime = call.getHandledAt();

            call.handle("あとから押した人");

            assertThat(call.getHandledBy())
                    .as("先に向かった人の名前が残ること")
                    .isEqualTo("先に向かった人");
            assertThat(call.getHandledAt())
                    .as("対応時刻も動かないこと（動くと待ち時間の集計が狂う）")
                    .isEqualTo(firstTime);
        }
    }

    @Nested
    @DisplayName("種類ごとの言葉")
    class Labels {

        @Test
        @DisplayName("お客さま側とスタッフ側で、言葉を分けている")
        void customerAndStaffLabelsDiffer() {
            // お客さまは「お会計をお願いする」と押す。
            // ホールには「テーブル1 お会計をご希望です」と出したい。
            // 同じ文字列を使い回すと、どちらかが不自然な日本語になる。
            assertThat(ServiceCallType.CHECKOUT.getCustomerLabel()).isEqualTo("お会計をお願いする");
            assertThat(ServiceCallType.CHECKOUT.getStaffLabel()).isEqualTo("お会計をご希望です");
        }

        @Test
        @DisplayName("★ 持っていく物は、ここに入れない")
        void onlyCallsThatNeedAPerson() {
            // お水・おしぼり・取り皿などは ¥0 の商品として注文に流し、厨房ボードに出す。
            // あちらには「出したか」という進行があり、注文の状態遷移がそのまま使えるため。
            // ここに足すと、金額も個数も無いものが 2 つの道を通ることになり、
            // どちらを見れば全部わかるのかが誰にも言えなくなる。
            assertThat(ServiceCallType.values())
                    .as("呼び出しは「人が向かう」用件だけ。2026-09-05 に店主と決めた振り分け")
                    .containsExactly(ServiceCallType.STAFF, ServiceCallType.CHECKOUT);
        }
    }
}
