package jp.komeko.order.service;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderLine;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 深夜料金と設定変更の縁切り（2026-09-07 の全体点検 #3）。
 *
 * <p><b>何が壊れていたか</b><br>
 * 伝票は割増率を来店時点でコピーする（スナップショット）のに、
 * 「どの注文が深夜帯か」の判定 {@code ShopSetting#isLateNight} は
 * <b>現在の割増率が 0 だと窓を見ずに即 false</b> だった。
 * そのため営業中に割増率を 10→0 に変えると、率 10 をコピーして開いた
 * OPEN 伝票からも、次の再計算で深夜料金が消えた。
 * お客さまに伝えた金額より安く締まる方向とはいえ、
 * 「設定変更は既存の伝票に影響しない」という原則の穴だった。
 *
 * <p><b>直したあとの役割分担</b><br>
 * 判定は「窓の中か」だけを見る。かけるかどうか・何%かは
 * 伝票のスナップショット率に従う（計算式はもともとそうなっていた。
 * {@code TableSession#recalculate} の {@code lateNightSurchargePercent > 0} 分岐）。
 *
 * <p>時刻窓そのものが現在設定から来ることは仕様として残している。
 * 窓を動かすときは価格変更と同じく「販売中止 → 変更 → 再開」の手順
 * （CLAUDE.md に追記済み）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("深夜料金の判定と設定変更の縁切り")
class LateNightRateSnapshotTest {

    @Autowired
    private TableService tableService;
    @Autowired
    private ShopSettingService shopSettingService;
    @Autowired
    private DiningTableRepository diningTableRepository;
    @Autowired
    private TableSessionRepository tableSessionRepository;
    @Autowired
    private OrderRepository orderRepository;

    private int originalRate;
    private int originalCharge;
    private LocalTime originalStart;
    private LocalTime originalEnd;

    @BeforeEach
    void snapshotSettings() {
        ShopSetting setting = shopSettingService.current();
        originalRate = setting.getLateNightSurchargePercent();
        originalCharge = setting.getTableChargePerGuest();
        originalStart = setting.getLateNightStartTime();
        originalEnd = setting.getLateNightEndTime();
        // 何時に走らせても「いま」が深夜帯に入るよう、窓を終日にする。
        // テーブルチャージも深夜対象に入る（openedAt が窓の中）ので、
        // 金額を ¥1,000 の 10% ちょうどにするためチャージは 0 にしておく
        setting.setLateNightStartTime(LocalTime.MIDNIGHT);
        setting.setLateNightEndTime(LocalTime.of(23, 59, 59));
        setting.setBusinessDayCutoverHour(0);
        setting.setTableChargePerGuest(0);
        shopSettingService.save(setting);
    }

    @AfterEach
    void restoreSettings() {
        ShopSetting setting = shopSettingService.current();
        setting.setLateNightSurchargePercent(originalRate);
        setting.setTableChargePerGuest(originalCharge);
        setting.setLateNightStartTime(originalStart);
        setting.setLateNightEndTime(originalEnd);
        shopSettingService.save(setting);
    }

    private void setCurrentRate(int percent) {
        ShopSetting setting = shopSettingService.current();
        setting.setLateNightSurchargePercent(percent);
        shopSettingService.save(setting);
    }

    /** 本番の OrderService#place と同じ手順で、¥1,000 の注文を 1 件積む。 */
    private TableSession openBillWithOneOrder(String tableName) {
        DiningTable table = diningTableRepository.save(new DiningTable(tableName, 4, 990));
        TableSession bill = tableService.openSession(table.getId(), 2);
        Order order = new Order(bill.getBusinessDate(), 990001, bill.getTaxRatePercent());
        order.setSession(bill);
        order.addLine(new OrderLine(1L, "深夜テスト品", 1000, 1, 5));
        order.recalculate();
        orderRepository.save(order);
        return bill;
    }

    private TableSession reload(TableSession bill) {
        // currentSession は表示と同じ道（applyCurrentAmounts → recalculate）を通る。
        // 画面が見る値そのものを確かめたいので、この入口から読む
        return tableService.currentSession(bill.getDiningTable().getId()).orElseThrow();
    }

    private void cleanUp(TableSession bill) {
        orderRepository.deleteAll(orderRepository.findAll().stream()
                .filter(o -> o.getSession() != null && bill.getId().equals(o.getSession().getId()))
                .toList());
        tableSessionRepository.deleteById(bill.getId());
        diningTableRepository.deleteById(bill.getDiningTable().getId());
    }

    @Test
    @DisplayName("★ 率 10 で開いた伝票は、現在の率を 0 にしても深夜料金が消えない")
    void openBillKeepsItsSnapshotWhenCurrentRateDropsToZero() {
        setCurrentRate(10);
        TableSession bill = openBillWithOneOrder("深夜A" + (System.nanoTime() % 100000));
        try {
            // まず前提：窓は終日・率 10 なので、¥1,000 の 10% が乗っている
            assertThat(reload(bill).getLateNightAmount())
                    .as("前提が崩れている（そもそも深夜料金が乗っていない）").isEqualTo(100);

            // 営業中に、現在の設定だけ 0% に変える（伝票のスナップショットは 10 のまま）
            setCurrentRate(0);

            // ★ ここが本題。判定が現在の率を見ていた頃は、ここで 0 円に化けた
            assertThat(reload(bill).getLateNightAmount())
                    .as("設定変更が OPEN 伝票の深夜料金を消した").isEqualTo(100);
            assertThat(reload(bill).isLateNightApplied()).isTrue();
        } finally {
            cleanUp(bill);
        }
    }

    @Test
    @DisplayName("★ 率 0 で開いた伝票は、現在の率を上げても 0 円のまま")
    void zeroSnapshotStaysZeroWhenCurrentRateRises() {
        setCurrentRate(0);
        TableSession bill = openBillWithOneOrder("深夜B" + (System.nanoTime() % 100000));
        try {
            setCurrentRate(10);

            // スナップショットが 0 なので、窓に入っていても金額は付かない。
            // ここが動くと「深夜料金なしの約束で入店した組」に後から乗ることになる
            assertThat(reload(bill).getLateNightAmount()).isZero();
            assertThat(reload(bill).isLateNightApplied()).isFalse();
        } finally {
            cleanUp(bill);
        }
    }
}
