package jp.komeko.order.seed;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.service.TableService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ 公開デモに、見学者を案内できる空席が必ず 1 卓は残っていること。
 *
 * <p><b>2026-09-27 に公開デモで行き止まりが起きました。</b>
 * 店主の報告は「お会計に進もうとすると頼んでない殻付きホタテバターが
 * 入ってて注文出来ない」。再現したところ、こうなっていました。
 *
 * <pre>
 *   空席 0 卓
 *     在卓       6 卓
 *     お会計待ち 2 卓
 *     バッシング 2 卓
 *   → DemoEntryController.pickTable() が相席に落ち、
 *     見学者が他人の伝票に座って、知らない品が伝票に入る
 * </pre>
 *
 * <p>原因は名前の食い違いでした。{@code DemoDataSeeder.STAGE_TABLE} が
 * 「カウンター1」のままで、{@code DataSeeder} が作る卓は「カウンター札1」。
 * <b>一致しないので 1 卓も予約できていませんでした。</b>
 *
 * <p>この種の食い違いは例外を出しません。黙って予約が外れ、
 * 公開デモを開くまで誰も気づけません。だからテストで押さえます。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:demo-free-table;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.demo-data=true",
        "app.demo-history-months=0",
        "app.seed-on-startup=true",
        "app.inventory.enabled=true",
        "app.backup.enabled=false",
        // 片付けの定期実行がテスト中に動くと、数えている途中で状態が変わる
        "app.demo-refresh-ms=3600000",
        "spring.devtools.restart.enabled=false"
})
@ActiveProfiles("dev")
@DisplayName("公開デモに空席が残っていること")
class DemoLeavesATableFreeTest {

    @Autowired
    private DiningTableRepository tables;

    @Autowired
    private TableService tableService;

    private boolean isFree(DiningTable t) {
        // DemoEntryController.isFree() と同じ条件。
        // 片付け待ちの卓は openSession が弾くので、空席として数えてはいけない
        return !t.isNeedsCleanup()
                && tableService.currentSession(t.getId()).isEmpty();
    }

    @Test
    @DisplayName("★★ 種データを撒いたあとも、案内できる卓が 1 卓以上ある")
    void thereIsAlwaysATableForAVisitor() {
        List<DiningTable> all = tables.findAll();
        assertThat(all).as("卓が 1 つも作られていない").isNotEmpty();

        List<String> free = all.stream().filter(this::isFree).map(DiningTable::getName).toList();

        assertThat(free)
                .as("案内できる卓が 0 です。この状態で /demo を開くと、"
                        + "見学者が他人の伝票に相席で座ります。"
                        + "全 %d 卓のうち 在卓・お会計待ち・片付け待ちで全部塞がっています",
                        all.size())
                .isNotEmpty();
    }

    @Test
    @DisplayName("★★ 撮影用に空けてある卓の名前が、実在する卓と一致している")
    void theStageTableNameMatchesARealTable() {
        // ★ ここが食い違っていたのが 2026-09-27 の原因です。
        //   名前で照合しているので、ずれても例外は出ません。
        //   DemoDataSeeder 側の定数は private なので、
        //   「空けてあるはずの卓が実際に空いているか」で確かめます。
        List<String> names = tables.findAll().stream().map(DiningTable::getName).toList();

        assertThat(names)
                .as("卓の名前が変わっています。DemoDataSeeder.STAGE_TABLE も"
                        + "一緒に直したか確かめてください")
                .contains("カウンター札1");

        DiningTable stage = tables.findAll().stream()
                .filter(t -> "カウンター札1".equals(t.getName()))
                .findFirst()
                .orElseThrow();

        assertThat(isFree(stage))
                .as("撮影用に空けてあるはずの「カウンター札1」が埋まっています。"
                        + "DemoDataSeeder.STAGE_TABLE の名前が実在の卓名とずれていないか"
                        + "確かめてください")
                .isTrue();
    }
}
