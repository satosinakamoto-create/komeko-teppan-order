package jp.komeko.order.seed;

import jakarta.persistence.EntityManager;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.Order;
import jp.komeko.order.domain.OrderLine;
import jp.komeko.order.domain.OrderStatus;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.inventory.domain.PaymentMethod;
import jp.komeko.order.inventory.domain.Purchase;
import jp.komeko.order.inventory.domain.PurchaseCategory;
import jp.komeko.order.inventory.service.PurchaseDraft;
import jp.komeko.order.inventory.service.PurchaseService;
import jp.komeko.order.inventory.service.RecipeService;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
import jp.komeko.order.service.ShopSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 過去 1 年ぶんの<b>売上と仕入れ</b>を作る、架空の帳簿。
 *
 * <p><b>なぜ要るのか</b><br>
 * 既存の仕込み（{@link DemoDataSeeder} と
 * {@code InventoryDemoDataSeeder}）が作るのは<b>直近 2 週間</b>だけです。
 * その状態で売上画面を開くと、
 * <ul>
 *   <li>月次の折れ線は点が 1 つ。6 か月・12 か月に切り替えても見た目が変わらない</li>
 *   <li>前月比はどこも「—」。比べる相手がいない</li>
 *   <li>先月に戻ると全部 0 円。仕入れ・経費も税理士画面も空</li>
 * </ul>
 * となり、<b>画面が正しいのか壊れているのかを判断できません</b>。
 * 数字の入っていない画面は、レイアウトの良し悪しも読めません。
 *
 * <p><b>作るのは既存の仕込みの手前だけです。</b>
 * どこまでを既存が埋めたかは日数で決め打ちにせず、
 * <b>すでにある伝票のいちばん古い営業日</b>を境目にします（{@link #fillUntil()}）。
 * 決め打ちにすると、向こうの仕込み量を変えた瞬間に、
 * 穴が開くか二重に入るかのどちらかになります。
 *
 * <p><b>それらしさのために入れていること</b>
 * <ul>
 *   <li>曜日で組数が違う（金土が山、水木は定休で 0）</li>
 *   <li>月ごとの季節の波（12 月がいちばん高く、2 月が低い）</li>
 *   <li>看板メニューほどよく出る（ランキングが自然に長い尾を持つ）</li>
 *   <li>着席と注文の時刻は 17 時〜翌 0 時に散らす。23 時以降の注文には深夜料金が乗る</li>
 *   <li>仕入れはその月の売上に対して原価 3 割前後になるように積む</li>
 * </ul>
 *
 * <p><b>何度走らせても同じ帳簿になります。</b>
 * 乱数の種は日付から作ります（{@code new Random(date.toEpochDay())}）。
 * 起動のたびに数字が変わると、画面を直したのかデータが変わっただけなのかを
 * 見分けられなくなるからです。
 *
 * <p><b>安全装置</b><br>
 * {@code dev} と {@code demo} でしか Bean が作られず、
 * さらに {@code app.demo-data=true} のときだけ動きます。
 * そのうえ<b>過去の伝票がすでにあれば何もせずに終わります</b>。
 * 本番プロファイルではクラスごと読み込まれないので、
 * 実店舗の帳簿に架空の売上が混ざることはありません。
 *
 * <p><b>{@code @Order(4)} — 既存の仕込みが全部済んでから走る</b><br>
 * 卓・メニュー（{@link DataSeeder}）と直近の伝票（{@link DemoDataSeeder}、
 * 在庫のサンプル）がそろってから、その手前を埋めます。
 * {@link ApplicationRunner} が複数あるとき Spring は順序を保証しません。
 */
@Component
@Profile({"dev", "demo"})
@org.springframework.core.annotation.Order(4)
public class SalesHistoryDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SalesHistoryDemoSeeder.class);

    /**
     * 何か月ぶんさかのぼるか。
     *
     * <p>既定の 13 か月は、売上画面の折れ線が 12 か月まで選べることに合わせた長さです
     * （いちばん古い月の前月比を出すのに、もう 1 か月ぶん要る）。
     *
     * <p><b>設定で変えられるようにしてあるのはテストのためです。</b>
     * {@code app.demo-data=true} で起動するテストはこのクラスも走らせるので、
     * 13 か月ぶん（伝票 3 千件あまり）を毎回書くと、
     * 数字を見ないテストまで一様に遅くなります。
     * 履歴が要らないテストは {@code app.demo-history-months=1} を指定してください。
     */
    private final int months;

    /**
     * この日数より古い伝票があれば「もう入れてある」とみなす。
     *
     * <p>既存の仕込みが作るのは直近 2 週間ぶんなので、そこから十分に離した値です。
     * 短くすると入れ直しを見送ってしまい、長くすると二重に入ります。
     */
    private static final int SEEDED_IF_OLDER_THAN_DAYS = 60;

    /**
     * この日数より新しい日は、レシピを登録した品を売らない。
     *
     * <p>在庫の仕込みは 10 日前の棚卸しを起点に残量を計算し、
     * 直近 4 週間の実績から「あと◯営業日」を出します。
     * その窓に架空の売上を大量に流し込むと、在庫の画面が全部 0 になります。
     * 窓より少し広めに取ってあります。
     */
    private static final int STOCK_WINDOW_DAYS = 30;

    /**
     * 曜日ごとの組数（月曜が先頭）。水木は定休なので 0。
     *
     * <p>ここを均等にすると日別の棒グラフが定規のように揃ってしまい、
     * 「集計が効いているのか、ただの飾りなのか」が読み取れません。
     */
    private static final int[] BILLS_BY_DAY = {9, 10, 0, 0, 15, 17, 11};

    /**
     * 月ごとの季節の波（%）。1 月から順。
     *
     * <p>12 月が忘年会で跳ね、1〜2 月が落ちる、という飲食店のかたちです。
     * 平らな 12 本より、こちらのほうが「グラフとして読めるか」を判断できます。
     */
    private static final int[] SEASON_PERCENT = {88, 84, 96, 100, 98, 92, 97, 90, 100, 104, 106, 120};

    private final DiningTableRepository tables;
    private final MenuItemRepository menuItems;
    private final TableSessionRepository sessions;
    private final OrderRepository orders;
    private final ShopSettingService shopSettings;

    /**
     * 時刻を巻き戻すためだけに使う。
     *
     * <p>注文時刻（{@code Order.createdAt}）と着席時刻（{@code TableSession.openedAt}）は
     * <b>業務コードから書き換えられないようにしてあります</b>。会計の証跡だからです。
     * けれど作ったばかりの伝票はどれも「たったいま」なので、そのまま会計すると
     * <ul>
     *   <li>半年前の伝票を開くと着席時刻が今日になっている</li>
     *   <li>深夜料金が、仕込みを走らせた時刻しだいで付いたり付かなかったりする</li>
     *   <li>時間帯別の売上が、営業時間と関係ない 1 本の棒になる</li>
     * </ul>
     * となり、やはり画面を見ても判断できません。
     * そこで<b>会計する前に</b> DB を直接更新して時刻を戻し、
     * そのうえで本番と同じ {@code close} を通します。
     * 金額の計算は本番のまま（{@code TableSession#recalculate} の 1 箇所）です。
     * dev / demo でしか作られないクラスなので、実店舗のデータには届きません。
     */
    private final EntityManager entityManager;

    /**
     * 仕入れの登録。
     *
     * <p>在庫モジュール（{@code app.inventory.enabled}）を切ると Bean が無いので
     * {@link ObjectProvider} で受けます。無ければ売上だけ作ります。
     * 売上が入るだけでも、売上画面とダッシュボードは判断できる状態になります。
     */
    private final ObjectProvider<PurchaseService> purchaseServiceProvider;

    /** レシピの登録状況を見るためだけに使う。無ければ在庫の心配も要らない。 */
    private final ObjectProvider<RecipeService> recipeServiceProvider;

    private final boolean enabled;

    public SalesHistoryDemoSeeder(DiningTableRepository tables,
                                  MenuItemRepository menuItems,
                                  TableSessionRepository sessions,
                                  OrderRepository orders,
                                  ShopSettingService shopSettings,
                                  EntityManager entityManager,
                                  ObjectProvider<PurchaseService> purchaseServiceProvider,
                                  ObjectProvider<RecipeService> recipeServiceProvider,
                                  @Value("${app.demo-history-months:13}") int months,
                                  @Value("${app.demo-data:false}") boolean enabled) {
        this.months = Math.max(1, months);
        this.recipeServiceProvider = recipeServiceProvider;
        this.tables = tables;
        this.menuItems = menuItems;
        this.sessions = sessions;
        this.orders = orders;
        this.shopSettings = shopSettings;
        this.entityManager = entityManager;
        this.purchaseServiceProvider = purchaseServiceProvider;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            seed();
        } catch (RuntimeException e) {
            // 「あると嬉しいデータ」が入らないことと、アプリが起動しないことは深刻さが違う。
            // 既存の仕込みクラスから受け継いだ原則。
            log.warn("過去の売上・仕入れの投入に失敗しました（アプリは通常どおり起動します）: {}",
                    e.toString());
        }
    }

    void seed() {
        // ★ 「もう入れたか」は今日から数える。埋めた範囲から数えてはいけない。
        //
        //   最初はここを「作る範囲の手前に伝票があるか」で見ていた。すると 2 回目の起動で
        //   境目が 13 か月ぶん過去にずれ、その手前は当然まだ空なので、
        //   さらに 13 か月ぶんを作ってしまう。起動のたびに帳簿が過去へ伸びていく。
        //   既存の仕込みが作るのは直近 2 週間だけなので、
        //   それより十分古い伝票があれば「このクラスがもう走った」と判断できる。
        LocalDate cutoff = shopSettings.currentBusinessDate().minusDays(SEEDED_IF_OLDER_THAN_DAYS);
        if (sessions.existsByBusinessDateBefore(cutoff)) {
            log.info("過去の伝票がすでにあるため、売上履歴の投入を見送りました");
            return;
        }
        LocalDate until = fillUntil();
        LocalDate from = YearMonth.from(until).minusMonths(months - 1L).atDay(1);

        if (tables.count() == 0 || menuItems.count() == 0) {
            log.info("卓かメニューがまだ無いため、売上履歴の投入を見送りました");
            return;
        }

        long totalSales = 0;
        int totalBills = 0;
        int months = 0;
        YearMonth last = YearMonth.from(until.minusDays(1));

        for (YearMonth month = YearMonth.from(from); !month.isAfter(last); month = month.plusMonths(1)) {
            Month result = fillMonth(month, from, until);
            if (result.bills() == 0) {
                continue;
            }
            totalSales += result.sales();
            totalBills += result.bills();
            months++;
            recordPurchasesFor(month, result.sales(), until.minusDays(1));

            // 1 か月ぶん書き終えたら永続化コンテキストを空にする。
            // 溜めたままだと Hibernate が変更検知のたびに全件を舐めるので、
            // 月を追うごとに遅くなる（件数の 2 乗で効く）。
            entityManager.flush();
            entityManager.clear();
        }

        log.warn("""

                ============================================================
                 架空の帳簿を {} か月ぶん入れました（{} 〜 {} の手前まで）。
                   ・伝票 {} 組 ／ 売上 {} 円
                   ・仕入れは、その月の売上に対して原価 3 割前後になるように積んであります

                 見どころ:
                   /admin/sales           月次の折れ線・前月比・商品ランキング
                   /inventory/purchases   月ごとの経費と実際原価率
                   /accountant            税理士に渡す仕訳（月を戻せます）
                ============================================================
                """, months, from, until, totalBills, totalSales);
    }

    /**
     * どこまで埋めるか（この日の<b>手前</b>まで作る）。
     *
     * <p>今の営業日の手前まで、つまり<b>昨日まで</b>です。
     * 今日は {@link DemoDataSeeder} が「営業中の景色」を作る日なので、そこには触りません。
     *
     * <p><b>既存の仕込みが埋めた日も、足りない分を足します。</b>
     * はじめは「すでにある伝票のいちばん古い営業日の手前まで」にしていましたが、
     * それだと直近 2 週間だけ 1 日の売上が 1/3 になり、
     * 月次のグラフに<b>説明のつかない段差</b>ができました。
     * 段差のあるデータでは「グラフが正しいのか、店が急に暇になったのか」を判断できません。
     */
    private LocalDate fillUntil() {
        return shopSettings.currentBusinessDate();
    }

    // ========================================================================
    //  売上
    // ========================================================================

    /** 1 か月ぶんの結果。 */
    private record Month(int bills, long sales) {
    }

    /** 時刻を巻き戻す対象。id と、そこに入れたい時刻。 */
    private record Backdate(Long id, LocalDateTime at) {
    }

    /** 伝票番号。実店舗では日ごとに振り直すが、ここは重複しなければよい。 */
    private int orderNumber = 10_000;

    private Month fillMonth(YearMonth month, LocalDate from, LocalDate until) {
        // 月をまたぐたびに読み直す。前の月の終わりで永続化コンテキストを空にしているので、
        // 持ち越した写しを使うと「切り離された実体」を保存しようとすることになる
        ShopSetting setting = shopSettings.currentReadOnly();
        List<DiningTable> allTables = tables.findAll();
        List<MenuItem> pool = popularityPool(false);
        List<MenuItem> recentPool = popularityPool(true);
        if (allTables.isEmpty() || pool.isEmpty()) {
            return new Month(0, 0);
        }
        LocalDate stockWindow = shopSettings.currentBusinessDate().minusDays(STOCK_WINDOW_DAYS);

        int season = SEASON_PERCENT[month.getMonthValue() - 1];
        List<Long> sessionIds = new ArrayList<>();
        List<Backdate> orderTimes = new ArrayList<>();
        List<Backdate> seatTimes = new ArrayList<>();

        for (LocalDate date = month.atDay(1); !date.isAfter(month.atEndOfMonth()); date = date.plusDays(1)) {
            if (date.isBefore(from) || !date.isBefore(until)) {
                continue;
            }
            // すでに伝票がある日は、足りない分だけ足す。
            // 既存の仕込みが埋めた直近 2 週間に段差を作らないため
            int groups = billsOn(date, season) - (int) sessions.countByBusinessDate(date);
            if (groups <= 0) {
                continue;
            }
            Random random = new Random(date.toEpochDay());

            // ★ 棚卸しより後の日は、レシピを登録した品を売らない。
            //
            //   在庫は「棚卸し＋仕入れ−消費」で毎回計算する。消費は売れた品のレシピから出る。
            //   ここで看板メニューを大量に売ると、食材が一気にマイナスまで減り、
            //   /inventory/ingredients の残量と「あと◯営業日」が全部 0 になる。
            //   このクラスが積む仕入れは食材に紐付けていない（帳簿にだけ入る）ので、
            //   減った分が戻ってこない。
            //   売上を見せるために在庫の画面を壊すのでは本末転倒なので、
            //   直近だけ品ぞろえを変える。ランキングに載る看板メニューは、
            //   もともと在庫側の仕込みが売っている。
            List<MenuItem> menu = date.isBefore(stockWindow) ? pool : recentPool;

            for (int g = 0; g < groups; g++) {
                DiningTable table = allTables.get(random.nextInt(allTables.size()));
                int guests = Math.max(1, Math.min(table.getCapacity(), 1 + random.nextInt(4)));
                TableSession session = sessions.save(new TableSession(table, date, guests, setting));

                // 17:00〜24:00 に着席。1 卓 1〜3 回の注文で、追い注文は 20〜60 分後
                LocalDateTime seated = date.atTime(17, 0).plusMinutes(random.nextInt(420));
                int orderCount = 1 + random.nextInt(3);
                for (int o = 0; o < orderCount; o++) {
                    Order order = new Order(date, orderNumber++, setting.getTaxRatePercent());
                    order.setSession(session);
                    // 1 回に 2〜4 品。1 品からにすると 1 組あたり 7 千円ほどにしかならず、
                    // 酒を出してテーブルチャージも取る店の客単価としては安すぎる
                    int lines = 2 + random.nextInt(3);
                    for (int l = 0; l < lines; l++) {
                        MenuItem item = menu.get(random.nextInt(menu.size()));
                        order.addLine(new OrderLine(item.getId(), item.getName(),
                                item.getPrice(), 1 + random.nextInt(2), item.getCookMinutes()));
                    }
                    order.recalculate();
                    order.changeStatus(OrderStatus.COOKING, "デモ");
                    order.changeStatus(OrderStatus.READY, "デモ");
                    order.changeStatus(OrderStatus.COMPLETED, "デモ");
                    orders.save(order);
                    // 伝票側のコレクションにも足す。伝票の合計は自分の orders を読むので、
                    // setSession だけだと逆側が空のままで、閉じた伝票が 0 円になる
                    session.getOrders().add(order);

                    orderTimes.add(new Backdate(order.getId(),
                            seated.plusMinutes(o * (20L + random.nextInt(40)))));
                }
                // 着席は最初の注文の 10 分前
                seatTimes.add(new Backdate(session.getId(), seated.minusMinutes(10)));
                sessionIds.add(session.getId());
            }
        }

        if (sessionIds.isEmpty()) {
            return new Month(0, 0);
        }

        backdate(orderTimes, seatTimes);

        long sales = 0;
        for (Long id : sessionIds) {
            TableSession session = sessions.findWithOrdersById(id).orElse(null);
            if (session == null) {
                continue;
            }
            // 閉店（翌 1:30）に会計。深夜料金は本番と同じ判定（注文ごとに注文時刻で決まる）
            session.close(session.getBusinessDate().plusDays(1).atTime(1, 30),
                    setting::isLateNight, "デモ", null);
            sessions.save(session);
            sales += session.getTotalAmount();
        }
        return new Month(sessionIds.size(), sales);
    }

    /** その日の組数。曜日の相場に季節の波を掛け、日ごとに少し揺らす。 */
    private int billsOn(LocalDate date, int seasonPercent) {
        int base = BILLS_BY_DAY[date.getDayOfWeek().getValue() - 1];
        if (base == 0) {
            return 0;
        }
        // ±2 組の揺らぎ。日付から決めるので、何度走らせても同じ
        int wobble = (int) (date.toEpochDay() % 5) - 2;
        return Math.max(1, base * seasonPercent / 100 + wobble);
    }

    /**
     * 着席時刻と注文時刻を DB へ直接書き戻す。
     *
     * <p>更新文は<b>永続化コンテキストを迂回して</b> DB へ飛びます。だから前後の掃除が要ります。
     * <ul>
     *   <li>先に {@code flush} しないと、まだ DB に出ていない行を更新することになり、
     *       0 件更新で静かに空振りする</li>
     *   <li>あとで {@code clear} しないと、メモリ上の写しは古い時刻のままなので、
     *       コミット時の変更検知が<b>巻き戻した時刻を元に戻してしまう</b></li>
     * </ul>
     * 掃除したあとに伝票を読み直してから会計します（{@link #fillMonth}）。
     */
    private void backdate(List<Backdate> orderTimes, List<Backdate> seatTimes) {
        entityManager.flush();
        for (Backdate b : orderTimes) {
            entityManager.createQuery(
                            "update Order o set o.createdAt = :at, o.updatedAt = :at where o.id = :id")
                    .setParameter("at", b.at())
                    .setParameter("id", b.id())
                    .executeUpdate();
        }
        for (Backdate b : seatTimes) {
            entityManager.createQuery(
                            "update TableSession s set s.openedAt = :at where s.id = :id")
                    .setParameter("at", b.at())
                    .setParameter("id", b.id())
                    .executeUpdate();
        }
        entityManager.clear();
    }

    /**
     * 人気の重みを付けたメニューの抽選箱。
     *
     * <p>全品から均等に選ぶと、商品ランキングが<b>横一線</b>になります。
     * 実際の店は上位の数品で売上の半分近くを作るので、
     * 前のほうに並んでいる品（＝看板メニュー）ほど箱に多く入れます。
     * 構成比という数字が意味を持つのは、この偏りがあるときだけです。
     *
     * @param excludeRecipes true ならレシピを登録した品を外す（在庫を減らさないため）
     */
    private List<MenuItem> popularityPool(boolean excludeRecipes) {
        Set<Long> skip = excludeRecipes ? menuItemIdsWithRecipe() : Set.of();
        List<MenuItem> sellable = menuItems.findAll().stream()
                .filter(MenuItem::isVisible)
                .filter(i -> !skip.contains(i.getId()))
                .toList();

        List<MenuItem> pool = new ArrayList<>();
        for (int i = 0; i < sellable.size(); i++) {
            int weight = (i < 12) ? 4 : (i < 36) ? 2 : 1;
            for (int w = 0; w < weight; w++) {
                pool.add(sellable.get(i));
            }
        }
        return pool;
    }

    /**
     * レシピが 1 行でも登録されている商品の id。
     *
     * <p>在庫モジュールを切っているときは空集合（消費のしくみ自体が無いので、
     * どの品を売っても在庫は減りません）。
     */
    private Set<Long> menuItemIdsWithRecipe() {
        RecipeService recipeService = recipeServiceProvider.getIfAvailable();
        if (recipeService == null) {
            return Set.of();
        }
        Set<Long> withRecipe = new HashSet<>();
        for (MenuItem item : menuItems.findAll()) {
            withRecipe.add(item.getId());
        }
        recipeService.menuItemsWithoutRecipe().forEach(item -> withRecipe.remove(item.getId()));
        return withRecipe;
    }

    // ========================================================================
    //  仕入れ
    // ========================================================================

    /** 仕入先と登録番号（null は登録番号なし＝インボイスを出せない先）。 */
    private static final String[][] FOOD_SUPPLIERS = {
            {"業務スーパー 学芸大学店", "T7000012050002"},
            {"築地魚河岸 まるさ", "T4010001034876"},
            {"たなか青果店", null},
            {"米粉専門 こめや", "T3010401088669"},
    };

    private static final String[][] DRINK_SUPPLIERS = {
            {"酒のあおき", "T8010001112233"},
            {"カクヤス 学芸大学店", "T1010001034567"},
    };

    private static final String[][] SUPPLY_SUPPLIERS = {
            {"アスクル", "T6010001030403"},
            {"業務スーパー 学芸大学店", "T7000012050002"},
    };

    private static final String[] FOOD_ITEMS = {
            "キャベツ", "豚バラスライス", "たまご 10個", "米粉 5kg", "米粉そば 30食",
            "茹でたこ足", "鶏もも 5kg", "豚ロース 4kg", "黒毛和牛上ホルモン 3kg", "牡蠣 2kg",
            "青ねぎ", "きのこ各種", "ミックスチーズ 2kg", "殻付きホタテ 30枚", "国産鶏皮 2kg",
    };

    private static final String[] DRINK_ITEMS = {
            "サッポロ赤星 中瓶 20本", "焼酎 甲類 20L", "ウィスキー 角 4L",
            "レモン果汁 1L", "ジャスミン茶 業務用", "炭酸水 24本",
    };

    private static final String[] SUPPLY_ITEMS = {
            "キッチンペーパー", "ゴミ袋 45L", "食器用洗剤", "割り箸 3000膳", "アルミホイル",
    };

    /**
     * その月の仕入れを積む。
     *
     * <p>金額は<b>その月の売上から逆算</b>します。
     * 決め打ちの金額を並べると、売上が伸びた月だけ原価率が落ちる、という
     * 実在しない動きになります。原価率の画面を見て判断するには、
     * 売上と仕入れが連動していなければ意味がありません。
     *
     * <p>割り当ては 食材 28% ／ 飲料・酒 7% ／ 消耗品 2% ＋ 光熱費。
     * 飲食店の相場（原価 30% 前後）に寄せてあります。
     */
    private void recordPurchasesFor(YearMonth month, long monthSales, LocalDate notAfter) {
        PurchaseService purchaseService = purchaseServiceProvider.getIfAvailable();
        if (purchaseService == null) {
            return;
        }
        // 今月ぶんを作るときは、月末までではなく<b>昨日まで</b>に収める。
        // ここを月末で切ると、9 月 5 日に起動したのに 9 月 29 日のレシートが並び、
        // 「まだ来ていない買い物の記録」という、あり得ない画面になる
        LocalDate lastDate = month.atEndOfMonth().isAfter(notAfter) ? notAfter : month.atEndOfMonth();
        if (lastDate.isBefore(month.atDay(1))) {
            return;
        }
        Random random = new Random(month.atDay(1).toEpochDay());

        spend(purchaseService, month, lastDate, random, PurchaseCategory.FOOD,
                FOOD_SUPPLIERS, FOOD_ITEMS, (int) (monthSales * 28 / 100), 6, 8);
        spend(purchaseService, month, lastDate, random, PurchaseCategory.DRINK,
                DRINK_SUPPLIERS, DRINK_ITEMS, (int) (monthSales * 7 / 100), 2, 10);
        spend(purchaseService, month, lastDate, random, PurchaseCategory.SUPPLIES,
                SUPPLY_SUPPLIERS, SUPPLY_ITEMS, (int) (monthSales * 2 / 100), 2, 10);

        // 電気は月末締めなので、月の途中ならまだ請求が来ていない。
        // 鉄板の店なので、夏の空調と冬の暖房でどちらも上がる
        if (lastDate.getDayOfMonth() >= 25) {
            int electricity = 46_000 + SEASON_PERCENT[month.getMonthValue() - 1] * 120;
            settle(purchaseService.record(new PurchaseDraft(
                    month.atDay(25), month.atDay(25), "東京電力エナジーパートナー",
                    electricity, PaymentMethod.BANK_TRANSFER, "T7010001008844",
                    null, null, null, null, true,
                    List.of(new PurchaseDraft.LineDraft(
                            month.getMonthValue() + "月分 電気料金", BigDecimal.ONE, electricity, 10,
                            null, PurchaseCategory.UTILITIES, null, null, false))), null), month);
        }
    }

    /**
     * 1 つの費目ぶんのレシートを作る。
     *
     * <p>{@code PurchaseService#record} を通すので、登録番号の検算も、証憑区分の推定も、
     * 控除率のスナップショットも本番とまったく同じ道を通ります。
     * デモ用の抜け道は作りません。見せている画面がそのまま本物であることが、
     * いちばんの説得材料だからです。
     *
     * @param lastDate   この日までの範囲にレシートを散らす（今月なら昨日まで）
     * @param budget     その月にこの費目で使う総額（税込）
     * @param receipts   何枚に分けるか
     * @param taxPercent 税率（食材は軽減税率の 8%、それ以外は 10%）
     */
    private void spend(PurchaseService purchaseService, YearMonth month, LocalDate lastDate,
                       Random random, PurchaseCategory category,
                       String[][] suppliers, String[] itemNames,
                       int budget, int receipts, int taxPercent) {
        if (budget <= 0 || receipts <= 0) {
            return;
        }
        int perReceipt = budget / receipts;
        int lastDay = lastDate.getDayOfMonth();

        for (int r = 0; r < receipts; r++) {
            // 月内にばらけさせる。同じ日に全部入っていると、日付での絞り込みが試せない
            int day = Math.max(1, Math.min(lastDay,
                    1 + (lastDay - 1) * r / Math.max(1, receipts - 1)));
            LocalDate on = month.atDay(day);
            String[] supplier = suppliers[random.nextInt(suppliers.length)];

            int lines = 3 + random.nextInt(3);
            List<PurchaseDraft.LineDraft> drafts = new ArrayList<>();
            int remaining = perReceipt;
            for (int l = 0; l < lines; l++) {
                // 最後の行で端数を吸収する。明細の合計とレシートの額が 1 円ずれるのを避ける
                int amount = (l == lines - 1) ? remaining : remaining / (lines - l);
                remaining -= amount;
                drafts.add(new PurchaseDraft.LineDraft(
                        itemNames[random.nextInt(itemNames.length)],
                        BigDecimal.ONE, amount, taxPercent, null,
                        category, null, null, false));
            }
            settle(purchaseService.record(new PurchaseDraft(
                    on, on, supplier[0], perReceipt, PaymentMethod.CASH, supplier[1],
                    null, null, null, null, true, drafts), null), month);
        }
    }

    /**
     * 「その月のうちに登録した」ことにする。
     *
     * <p>{@code PurchaseService} は<b>登録した時刻</b>と受領日を比べて、
     * 入力期限（電子帳簿保存法）を過ぎていれば「紙の保管が必要」を立てます。
     * 正しい判定ですが、過去 1 年ぶんをいま一度に流し込むと
     * <b>全部のレシートに警告が立ち</b>、画面が警告で埋まって何も読めなくなります。
     * 架空の帳簿は「その月に入力していた」体にするのが自然です。
     *
     * <p>ただし<b>各月の 1 枚目だけは警告を残します</b>。
     * 全部消すと、この機能が画面のどこに出るのかを確かめられません。
     * 見せたい状態を消さない、というのは既存の仕込みクラスと同じ考え方です。
     */
    private void settle(Purchase purchase, YearMonth month) {
        if (warnedMonths.add(month)) {
            return;
        }
        purchase.setPaperRetentionRequired(false);
    }

    /** すでに「紙の保管が必要」を 1 枚残した月。 */
    private final Set<YearMonth> warnedMonths = new HashSet<>();
}
