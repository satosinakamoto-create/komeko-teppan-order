package jp.komeko.order.inventory.service;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.inventory.InventoryTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 実際原価率の「分母」のテスト。
 *
 * <p><b>何を守っているか＝原価率の分母は、売上画面と同じ数字であること。</b><br>
 * はじめは受渡済みの注文を合計していましたが、それだとテーブルチャージ
 * （この店では 1 人 450 円）と深夜料金が入らず、売上画面と違う「売上」で
 * 原価率を割っていました。原価率は税理士に見せる数字なので、
 * 画面ごとに食い違う分母は使えません（2026-08-31 に切り替え）。
 *
 * <p>この置き方には将来の意味もあります。食べ放題のプラン料金も、
 * クーポンの値引きも、伝票レベルの加減算として設計してある（設計文書 10 章）ので、
 * 分母が伝票合計なら<b>それらが入った日も原価率は勝手に正しい</b>。
 *
 * <p>ほかのテストのデータと混ざらないよう、誰も使わない過去の日付を使います
 * （営業日の数え方などが全店横断の集計であるため。RecipeConsumptionTest と同じ工夫）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("実際原価率の分母（売上の数え方）")
class PurchaseSummarySalesTest {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private InventoryTestFixture fixture;

    @Test
    @DisplayName("★ 売上にはテーブルチャージが入る（＝売上画面と同じ数字で割る）")
    void sales_include_table_charge() {
        LocalDate day = LocalDate.now().minusDays(500);
        MenuItem item = fixture.createMenuItem("分母テスト用そば-" + System.nanoTime(), 1000);

        // 1000円 × 2 個 ＋ チャージ 450円 × 3 人 = 3,350 円
        TableSession bill = fixture.sellAndClose(item, day, 2, 3, 450);
        assertThat(bill.getTotalAmount()).isEqualTo(3350);

        PurchaseSummary summary = purchaseService.summarize(day, day);

        // 注文の合計（2000円）ではなく、会計で確定した 3,350 円。
        // ここが 2000 になっていたら、分母が注文合計に戻っている。
        assertThat(summary.salesIncludingTax()).isEqualTo(3350);
    }

    @Test
    @DisplayName("開いている伝票は売上に数えない（金額がまだ動くため）")
    void open_bills_are_not_sales() {
        LocalDate day = LocalDate.now().minusDays(510);
        MenuItem item = fixture.createMenuItem("分母テスト用うどん-" + System.nanoTime(), 800);

        // placeOrder は伝票を開いたままにする（会計していない）
        fixture.placeOrder(item, day, 3, OrderStatus.COMPLETED);

        PurchaseSummary summary = purchaseService.summarize(day, day);
        assertThat(summary.salesIncludingTax()).isZero();
    }

    @Test
    @DisplayName("期間の外の会計は入らない")
    void bills_outside_the_period_are_excluded() {
        LocalDate day = LocalDate.now().minusDays(520);
        MenuItem item = fixture.createMenuItem("分母テスト用もち-" + System.nanoTime(), 500);
        fixture.sellAndClose(item, day, 1, 2, 450);

        // 前日で締めた集計には入らない
        PurchaseSummary summary = purchaseService.summarize(day.minusDays(7), day.minusDays(1));
        assertThat(summary.salesIncludingTax()).isZero();
    }
}
