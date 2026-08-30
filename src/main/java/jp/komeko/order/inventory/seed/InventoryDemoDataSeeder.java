package jp.komeko.order.inventory.seed;

import jp.komeko.order.domain.*;
import jp.komeko.order.inventory.domain.*;
import jp.komeko.order.inventory.repository.IngredientRepository;
import jp.komeko.order.inventory.service.*;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
import jp.komeko.order.service.ShopSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 打ち合わせで見せるための、仕入れ・在庫・原価のサンプルデータ。
 *
 * <p><b>なぜ要るのか</b><br>
 * 作った機能を人に見せるとき、いちばん伝わらないのが<b>空っぽの画面</b>です。
 * 「ここに原価率が出ます」と口で言っても伝わりません。
 * 実際に数字が並んでいて、赤いバッジが付いていて、
 * 「あと 2 営業日」と書いてあるから、はじめて話が前に進みます。
 *
 * <p><b>入れるのは「説明したいことが起きている状態」</b><br>
 * きれいに揃ったデータではありません。わざと次の状態を混ぜてあります。
 * どれもこのシステムが答えを持っている問題で、<b>質問されたら見せられる</b>ようにするためです。
 * <ul>
 *   <li>1 枚のレシートに 8% と 10% が混ざっている（スーパーの日常）</li>
 *   <li>登録番号のない仕入れ（経過措置 80% が効く）</li>
 *   <li>入り数をまだ教わっていない品目（黄色の「教えてください」）</li>
 *   <li>残量が警告を下回った食材（赤いバッジ）</li>
 *   <li>レシピ未登録のメニュー（予測が甘くなる、という警告）</li>
 *   <li>廃棄の記録（棚卸しでつじつま合わせせず、理由つきで残す）</li>
 * </ul>
 *
 * <p><b>安全装置</b><br>
 * このクラスは {@code dev} と {@code demo} でしか作られず、
 * さらに {@code app.demo-data=true} のときにしか動きません。
 * そのうえ<b>食材が 1 つでも登録されていれば何もせずに終わります</b>。
 * 実店舗の DB に対して誤って走らせても、既存の記録を荒らしません。
 * 既存の {@code DemoDataSeeder} と同じ考え方です。
 *
 * <p><b>{@code @Order(3)} — 卓とメニューが揃ってから走る</b><br>
 * レシピは既存のメニュー商品に紐づけるので、
 * {@code DataSeeder}（土台）と {@code DemoDataSeeder}（当日の伝票）のあとに走らせます。
 * {@link ApplicationRunner} が複数あるとき Spring は順序を保証しないので、
 * 順番に意味があるなら必ず書きます（既存クラスのコメントに、
 * 書かなかったせいで公開デモが空になった記録が残っています）。
 */
@Component
@Profile({"dev", "demo"})
@org.springframework.core.annotation.Order(3)
public class InventoryDemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InventoryDemoDataSeeder.class);

    /** 過去の売上を何営業日ぶん作るか。予測の窓（4 週間）に収まる範囲。 */
    private static final int BUSINESS_DAYS = 12;

    /** 棚卸しをした日（今日から何日前か）。ここが在庫計算の起点になる。 */
    private static final int STOCKTAKE_DAYS_AGO = 10;

    private final IngredientRepository ingredients;
    private final IngredientService ingredientService;
    private final PurchaseService purchaseService;
    private final StockService stockService;
    private final RecipeService recipeService;
    private final MenuItemRepository menuItems;
    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;
    private final OrderRepository orders;
    private final ShopSettingService shopSettings;
    private final boolean enabled;

    public InventoryDemoDataSeeder(IngredientRepository ingredients,
                                   IngredientService ingredientService,
                                   PurchaseService purchaseService,
                                   StockService stockService,
                                   RecipeService recipeService,
                                   MenuItemRepository menuItems,
                                   DiningTableRepository tables,
                                   TableSessionRepository sessions,
                                   OrderRepository orders,
                                   ShopSettingService shopSettings,
                                   @Value("${app.demo-data:false}") boolean enabled) {
        this.ingredients = ingredients;
        this.ingredientService = ingredientService;
        this.purchaseService = purchaseService;
        this.stockService = stockService;
        this.recipeService = recipeService;
        this.menuItems = menuItems;
        this.tables = tables;
        this.sessions = sessions;
        this.orders = orders;
        this.shopSettings = shopSettings;
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
            // ★ サンプルデータが入らないことと、アプリが起動しないことは深刻さが違う。
            //   「あると嬉しい処理」で全体を止めない、が既存クラスから受け継いだ原則。
            log.warn("在庫のサンプルデータ投入に失敗しました（アプリは通常どおり起動します）: {}",
                    e.toString());
        }
    }

    void seed() {
        if (!ingredients.findAll().isEmpty()) {
            log.info("食材がすでに登録されているため、在庫のサンプルデータ投入を見送りました");
            return;
        }

        Map<String, Ingredient> pantry = createIngredients();
        int receipts = createPurchases(pantry);
        createStocktakes(pantry);
        List<MenuItem> withRecipe = new ArrayList<>();
        int recipeLines = createRecipes(pantry, withRecipe);
        int soldDays = createPastSales(withRecipe);

        log.warn("""

                ============================================================
                 打ち合わせ用のサンプルデータを入れました。
                   ・食材 {} 品目、レシピ {} 行
                   ・仕入れレシート {} 枚（8%と10%の混在、インボイスなしを含む）
                   ・棚卸し（{}日前）と廃棄の記録
                   ・過去 {} 営業日ぶんの売上

                 見せどころ:
                   /inventory/purchases   月の経費と実際原価率、電帳法の検索
                   /inventory/ingredients 残量と「あと◯営業日」、赤いバッジ
                   /inventory/recipes     前職のエクセルの原価表そのもの
                   /inventory/tax-rates   税制改正を画面から登録する
                ============================================================
                """, pantry.size(), recipeLines, receipts, STOCKTAKE_DAYS_AGO, soldDays);
    }

    // ========================================================================
    //  食材
    // ========================================================================

    private Map<String, Ingredient> createIngredients() {
        Map<String, Ingredient> pantry = new HashMap<>();
        int order = 0;
        for (Object[] row : new Object[][]{
                // 名前, 単位, 警告残量（null なら量では警告しない）
                {"キャベツ", IngredientUnit.GRAM, "3000"},
                {"米粉", IngredientUnit.GRAM, "2000"},
                {"豚バラスライス", IngredientUnit.GRAM, "1000"},
                {"卵", IngredientUnit.PIECE, "24"},
                {"米粉そば", IngredientUnit.BAG, "10"},
                {"天かす", IngredientUnit.GRAM, null},
                {"青ねぎ", IngredientUnit.GRAM, "500"},
                {"エリンギ", IngredientUnit.GRAM, "300"},
                {"たこ", IngredientUnit.GRAM, "500"},
                {"お好みソース", IngredientUnit.MILLILITER, "1000"},
                {"マヨネーズ", IngredientUnit.MILLILITER, null},
                {"かつお節", IngredientUnit.GRAM, null},
        }) {
            Ingredient ingredient = new Ingredient((String) row[0], (IngredientUnit) row[1]);
            if (row[2] != null) {
                ingredient.setLowThresholdQty(new BigDecimal((String) row[2]));
            }
            ingredient.setSortOrder(order++);
            pantry.put(ingredient.getName(), ingredients.save(ingredient));
        }
        return pantry;
    }

    // ========================================================================
    //  仕入れ
    // ========================================================================

    /**
     * レシートを 7 枚。<b>わざと種類を散らしています。</b>
     *
     * <p>{@link PurchaseService#record} を通すので、登録番号の検算も、
     * 証憑区分の推定も、控除率のスナップショットも、入力期限の判定も、
     * 本番とまったく同じ道を通ります。<b>デモ用の抜け道は作りません。</b>
     * 見せている画面がそのまま本物であることが、いちばんの説得材料だからです。
     */
    private int createPurchases(Map<String, Ingredient> pantry) {
        LocalDate today = LocalDate.now();

        // ── 1. 業務スーパー: 食材（8%）と消耗品（10%）が 1 枚に混ざる ──
        //    「レシート単位で税率を持てない」ことの実物。
        record(today.minusDays(12), "業務スーパー 学芸大学店", "T7000012050002", PaymentMethod.CASH, List.of(
                line("キャベツ 1/2", "2", 216, 8, pantry.get("キャベツ"), "1600"),
                line("豚バラスライス", "1", 594, 8, pantry.get("豚バラスライス"), "500"),
                line("たまご 10個", "2", 518, 8, pantry.get("卵"), "20"),
                line("キッチンペーパー", "1", 328, 10, null, null),
                line("ゴミ袋 45L", "1", 218, 10, null, null)));

        // ── 2. 八百屋（登録番号なし）: 経過措置 80% が効く ──
        //    税理士がいちばん気にするところ。実物で見せられるようにしておく。
        record(today.minusDays(11), "たなか青果店", null, PaymentMethod.CASH, List.of(
                line("キャベツ", "3", 450, 8, pantry.get("キャベツ"), "3200"),
                line("青ねぎ", "2", 320, 8, pantry.get("青ねぎ"), "400"),
                // ★ エリンギは「120円」としか書かれていない。グラム数は印字なし。
                //   ここが item_alias で 1 回だけ教える対象になる。
                line("エリンギ", "1", 120, 8, pantry.get("エリンギ"), "100")));

        // ── 3. 米粉の仕入れ（まとめ買い） ──
        record(today.minusDays(9), "米粉専門 こめや", "T3010401088669", PaymentMethod.BANK_TRANSFER, List.of(
                line("米粉 5kg", "1", 2160, 8, pantry.get("米粉"), "5000"),
                line("米粉そば 30食", "1", 5400, 8, pantry.get("米粉そば"), "30")));

        // ── 4. 鮮魚（たこ） ──
        record(today.minusDays(7), "築地魚河岸 まるさ", "T4010001034876", PaymentMethod.CREDIT_CARD, List.of(
                line("茹でたこ足", "2", 2160, 8, pantry.get("たこ"), "1200")));

        // ── 5. 調味料・乾物 ──
        record(today.minusDays(5), "業務スーパー 学芸大学店", "T7000012050002", PaymentMethod.CASH, List.of(
                line("お好みソース 2.1kg", "1", 862, 8, pantry.get("お好みソース"), "2100"),
                line("マヨネーズ 1kg", "1", 538, 8, pantry.get("マヨネーズ"), "1000"),
                line("かつお節 500g", "1", 1490, 8, pantry.get("かつお節"), "500"),
                line("天かす 1kg", "1", 646, 8, pantry.get("天かす"), "1000"),
                line("食器用洗剤", "1", 218, 10, null, null)));

        // ── 6. 酒屋（原価率の分母に入れない費目） ──
        record(today.minusDays(4), "酒のあおき", "T8010001112233", PaymentMethod.BANK_TRANSFER, List.of(
                lineOf("サッポロ赤星 中瓶 20本", "1", 5280, 10, PurchaseCategory.DRINK),
                lineOf("焼酎 甲類 20L", "1", 3980, 10, PurchaseCategory.DRINK)));

        // ── 7. 光熱費（在庫にも原価にも入らないが、経費としては要る） ──
        record(today.minusDays(2), "東京電力エナジーパートナー", "T7010001008844",
                PaymentMethod.BANK_TRANSFER, List.of(
                        lineOf("電気料金 8月分", "1", 48620, 10, PurchaseCategory.UTILITIES)));

        // ── 8. 入り数をまだ教わっていない品目を 1 つ残す ──
        //    「教えてください」の黄色い状態を実物で見せるため。
        //    全部きれいに埋まっていると、この機能の意味が伝わらない。
        record(today.minusDays(1), "たなか青果店", null, PaymentMethod.CASH, List.of(
                line("大葉", "1", 138, 8, null, null),
                line("エリンギ", "2", 240, 8, pantry.get("エリンギ"), "200")));

        createUntrackedFoodPurchases(today);
        return receiptCount;
    }

    /**
     * 食材マスタに登録していない材料の仕入れ。
     *
     * <p><b>これを入れないと実際原価率が 5% になります。</b>
     * 原価率は「食材の仕入額 ÷ 売上」なので、分子には<b>店で買う材料が全部</b>要ります。
     * ところが在庫として追いかけている食材は 12 品目だけで、
     * メニューは 94 品あります。追いかけている分だけ計上すると、
     * 飲食店としてありえない数字が出ます（実際は 28〜35% が相場）。
     *
     * <p>実店舗でもまったく同じことが起きます。
     * 最初から全部の食材を登録できる店はありません。
     * <b>紐付けていない行も経費としては正しく計上される</b>という設計が、
     * ここで効いています。在庫は追えなくても、帳簿は最初から正しい。
     *
     * <p>だからこの行には食材を紐付けていません（{@code ingredient = null}）。
     * 在庫には積まれず、原価率と月次の経費にだけ入ります。
     */
    private void createUntrackedFoodPurchases(LocalDate today) {
        record(today.minusDays(10), "築地魚河岸 まるさ", "T4010001034876", PaymentMethod.CREDIT_CARD, List.of(
                lineOf("殻付きホタテ 30枚", "1", 12960, 8, PurchaseCategory.FOOD),
                lineOf("国産鶏皮 2kg", "1", 2160, 8, PurchaseCategory.FOOD),
                lineOf("鶏せせり 2kg", "1", 4320, 8, PurchaseCategory.FOOD),
                lineOf("国産砂肝 2kg", "1", 2700, 8, PurchaseCategory.FOOD),
                lineOf("国産豚たん 2kg", "1", 5400, 8, PurchaseCategory.FOOD),
                lineOf("黒毛和牛上ホルモン 3kg", "1", 10800, 8, PurchaseCategory.FOOD)));

        record(today.minusDays(6), "たなか青果店", null, PaymentMethod.CASH, List.of(
                lineOf("大根 10本", "1", 1580, 8, PurchaseCategory.FOOD),
                lineOf("きゅうり 20本", "1", 1400, 8, PurchaseCategory.FOOD),
                lineOf("トマト 3kg", "1", 2160, 8, PurchaseCategory.FOOD),
                lineOf("わかめ 1kg", "1", 1890, 8, PurchaseCategory.FOOD),
                lineOf("きのこ各種", "1", 3240, 8, PurchaseCategory.FOOD),
                lineOf("じゃがいも 10kg", "1", 2380, 8, PurchaseCategory.FOOD),
                lineOf("牡蠣 2kg", "1", 5400, 8, PurchaseCategory.FOOD)));

        record(today.minusDays(3), "業務スーパー 学芸大学店", "T7000012050002", PaymentMethod.CASH, List.of(
                lineOf("たこ焼き用たこ 3kg", "1", 8640, 8, PurchaseCategory.FOOD),
                lineOf("ミックスチーズ 2kg", "1", 2380, 8, PurchaseCategory.FOOD),
                lineOf("チョリソー 100本", "1", 4320, 8, PurchaseCategory.FOOD),
                lineOf("うどん玉 50食", "1", 2700, 8, PurchaseCategory.FOOD),
                lineOf("鶏もも 5kg", "1", 5400, 8, PurchaseCategory.FOOD),
                lineOf("豚ロース 4kg", "1", 8640, 8, PurchaseCategory.FOOD),
                lineOf("ラップ・アルミホイル", "1", 1580, 10, PurchaseCategory.SUPPLIES)));
    }

    /** 書き出したレシートの枚数。起動ログに出すだけのもの。 */
    private int receiptCount;

    private void record(LocalDate on, String store, String regNumber,
                        PaymentMethod payment, List<PurchaseDraft.LineDraft> lines) {
        int total = 0;
        for (PurchaseDraft.LineDraft line : lines) {
            total += line.amount();
        }
        purchaseService.record(new PurchaseDraft(
                on, on, store, total, payment, regNumber,
                null,     // 証憑区分はサービスに推定させる（本番と同じ道を通す）
                null, null, null, true, lines), null);
        receiptCount++;
    }

    /** 食材に紐づく明細。 */
    private PurchaseDraft.LineDraft line(String itemText, String quantity, int amount,
                                         int taxRate, Ingredient ingredient, String stockQty) {
        return new PurchaseDraft.LineDraft(
                itemText, new BigDecimal(quantity), amount, taxRate, null,
                PurchaseCategory.FOOD,
                ingredient != null ? ingredient.getId() : null,
                stockQty != null ? new BigDecimal(stockQty) : null,
                true);
    }

    /** 食材に紐づかない明細（消耗品・酒・光熱費）。 */
    private PurchaseDraft.LineDraft lineOf(String itemText, String quantity, int amount,
                                           int taxRate, PurchaseCategory category) {
        return new PurchaseDraft.LineDraft(
                itemText, new BigDecimal(quantity), amount, taxRate, null,
                category, null, null, false);
    }

    // ========================================================================
    //  棚卸しと廃棄
    // ========================================================================

    /**
     * 棚卸しと廃棄。
     *
     * <p>実測値は<b>わざと理論値とずらしています</b>。
     * 打ち粉が飛び、まかないに回り、目分量でぶれるのが現場だからです。
     * ぴったり合うデータを見せると「この数字は作り物だ」と伝わってしまいます。
     */
    private void createStocktakes(Map<String, Ingredient> pantry) {
        LocalDate takenOn = LocalDate.now().minusDays(STOCKTAKE_DAYS_AGO);

        for (Object[] row : new Object[][]{
                {"キャベツ", "8500"},
                {"米粉", "3000"},
                {"豚バラスライス", "3400"},
                {"卵", "90"},
                {"米粉そば", "20"},
                {"天かす", "700"},
                {"青ねぎ", "2400"},
                {"エリンギ", "900"},
                {"たこ", "1500"},
                {"お好みソース", "1600"},
                {"マヨネーズ", "800"},
                {"かつお節", "380"},
        }) {
            Ingredient ingredient = pantry.get((String) row[0]);
            if (ingredient != null) {
                stockService.recordStocktake(ingredient.getId(), takenOn,
                        new BigDecimal((String) row[1]), "月初の棚卸し", null);
            }
        }

        // 廃棄とまかない。棚卸しでつじつまを合わせず、理由つきで残す。
        stockService.recordAdjustment(pantry.get("キャベツ").getId(),
                LocalDate.now().minusDays(6), new BigDecimal("-800"),
                StocktakeReason.WASTE, "外葉が傷んでいた", null);
        stockService.recordAdjustment(pantry.get("豚バラスライス").getId(),
                LocalDate.now().minusDays(3), new BigDecimal("-300"),
                StocktakeReason.STAFF_MEAL, "まかない", null);
    }

    // ========================================================================
    //  レシピ
    // ========================================================================

    /**
     * レシピ。<b>全部は登録しません。</b>
     *
     * <p>「登録した商品の分から順に効く」「未登録があると予測が甘くなる」という
     * 設計の要点は、<b>実際に未登録が残っている状態</b>でしか説明できません。
     * 全品そろったデータを見せると、いちばん伝えたい注意書きが伝わりません。
     */
    private int createRecipes(Map<String, Ingredient> pantry, List<MenuItem> registered) {
        int created = 0;
        created += recipeFor("肉玉米粉そば", pantry, registered, new String[][]{
                {"キャベツ", "180"}, {"米粉", "90"}, {"豚バラスライス", "60"},
                {"卵", "1"}, {"米粉そば", "1"}, {"天かす", "15"},
                {"お好みソース", "25"}, {"マヨネーズ", "12"}, {"かつお節", "3"}});
        created += recipeFor("ねぎたっぷり米粉そば", pantry, registered, new String[][]{
                {"キャベツ", "150"}, {"米粉", "90"}, {"青ねぎ", "80"},
                {"卵", "1"}, {"米粉そば", "1"}, {"天かす", "15"},
                {"お好みソース", "25"}, {"かつお節", "3"}});
        created += recipeFor("濃厚！国産豚ぺい焼", pantry, registered, new String[][]{
                {"卵", "2"}, {"豚バラスライス", "80"}, {"青ねぎ", "20"},
                {"お好みソース", "20"}, {"マヨネーズ", "15"}});
        created += recipeFor("たこときのこの塩たれ焼", pantry, registered, new String[][]{
                {"たこ", "90"}, {"エリンギ", "70"}, {"青ねぎ", "15"}});
        created += recipeFor("たこのねぎまみれ", pantry, registered, new String[][]{
                {"たこ", "80"}, {"青ねぎ", "60"}});
        return created;
    }

    private int recipeFor(String menuItemName, Map<String, Ingredient> pantry,
                          List<MenuItem> registered, String[][] rows) {
        MenuItem item = findMenuItem(menuItemName);
        if (item == null) {
            // メニューの名前が変わっていても、ここで止まらない。
            // サンプルデータのために起動を妨げるのは本末転倒。
            log.debug("レシピの対象メニューが見つかりませんでした: {}", menuItemName);
            return 0;
        }
        int created = 0;
        for (String[] row : rows) {
            Ingredient ingredient = pantry.get(row[0]);
            if (ingredient != null) {
                recipeService.addLine(item.getId(), ingredient.getId(), new BigDecimal(row[1]), null);
                created++;
            }
        }
        if (created > 0) {
            registered.add(item);
        }
        return created;
    }

    private MenuItem findMenuItem(String name) {
        for (MenuItem item : menuItems.findAll()) {
            if (name.equals(item.getName())) {
                return item;
            }
        }
        return null;
    }

    // ========================================================================
    //  過去の売上
    // ========================================================================

    /**
     * 過去の売上。<b>これが無いと原価率も予測も出ません。</b>
     *
     * <p>実際原価率は「食材の仕入額 ÷ 売上」なので分母が要ります。
     * 「あと◯営業日」は直近 4 週間の消費実績から出すので、
     * 売れた記録が無ければ何も表示されません。
     * 仕入れだけ入れて満足すると、<b>見せたい画面がふたつとも空になります</b>。
     *
     * <p>営業日はとびとびにします（水木定休のつもり）。
     * 連続した日付を作ると「営業日を実績から数えている」ことが伝わりません。
     */
    private int createPastSales(List<MenuItem> withRecipe) {
        List<MenuItem> sellable = new ArrayList<>();
        for (MenuItem item : menuItems.findAll()) {
            if (item.isVisible()) {
                sellable.add(item);
            }
        }
        if (sellable.isEmpty() || tables.findAll().isEmpty()) {
            return 0;
        }

        ShopSetting setting = shopSettings.currentReadOnly();
        DiningTable table = tables.findAll().get(0);
        int orderNumber = 500;
        int days = 0;

        for (int back = 1; back <= 25 && days < BUSINESS_DAYS; back++) {
            LocalDate businessDate = LocalDate.now().minusDays(back);
            // 水・木は定休。カレンダーを設定に持たなくても、
            // 「注文が無い日」として自然に営業日から外れる。
            if (businessDate.getDayOfWeek().getValue() == 3
                    || businessDate.getDayOfWeek().getValue() == 4) {
                continue;
            }
            days++;

            TableSession session = sessions.save(
                    new TableSession(table, businessDate, 3, setting));

            // 1 営業日に 6 組ぶん。数量は日によって少し揺らす。
            int groups = 5 + (back % 3);
            for (int g = 0; g < groups; g++) {
                Order order = new Order(businessDate, orderNumber++, setting.getTaxRatePercent());
                order.setSession(session);
                for (int i = 0; i < 3; i++) {
                    // ★ 3 品のうち 2 品はレシピを登録した看板メニューから出す。
                    //
                    //   全 89 品から均等に選ぶと、レシピのある 5 品はめったに売れず、
                    //   食材がほとんど減りません。実際それで「あと 1173 営業日」という
                    //   数字が出ました。看板メニューが実際よく出る、という
                    //   店の姿に寄せたほうが、画面の数字も現実的になります。
                    MenuItem item = (i == 0 && !withRecipe.isEmpty())
                            ? withRecipe.get((back * 3 + g) % withRecipe.size())
                            : sellable.get((back * 7 + g * 3 + i) % sellable.size());
                    int quantity = 1 + ((back + g + i) % 2);
                    order.addLine(new OrderLine(item.getId(), item.getName(),
                            item.getPrice(), quantity, item.getCookMinutes()));
                }
                order.recalculate();
                order.changeStatus(OrderStatus.COOKING, "デモ");
                order.changeStatus(OrderStatus.READY, "デモ");
                order.changeStatus(OrderStatus.COMPLETED, "デモ");
                orders.save(order);
            }

            // 会計まで済ませて閉じる。開いたままだと、既存のデモデータ投入が
            // 「開いている伝票がある」と判断して見送ってしまう。
            session.close(businessDate.atTime(23, 30), LateNightPolicy.NONE, "デモ", null);
            sessions.save(session);
        }
        return days;
    }
}
