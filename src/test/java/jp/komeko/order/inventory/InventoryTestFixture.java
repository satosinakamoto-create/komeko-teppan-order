package jp.komeko.order.inventory;

import jp.komeko.order.domain.*;
import jp.komeko.order.inventory.domain.EvidenceType;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.PaymentMethod;
import jp.komeko.order.inventory.domain.PurchaseCategory;
import jp.komeko.order.inventory.service.PurchaseDraft;
import jp.komeko.order.inventory.service.PurchaseService;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DiningTableRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.OrderRepository;
import jp.komeko.order.repository.TableSessionRepository;
import jp.komeko.order.service.ShopSettingService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 在庫まわりのテストで使う「本物のデータ」を作る係。
 *
 * <p><b>なぜモックではなく本物を作るのか</b><br>
 * 消費の計算は既存の注文データ（{@code orders} と {@code order_line}）を
 * 読んで成り立っています。モックで置き換えてしまうと、
 * <b>問い合わせの条件を書き間違えても気づけません</b>。
 * キャンセルの除外も、営業日の数え方も、実際に SQL が走って初めて確かめられます。
 *
 * <p>注文を 1 件作るのに卓と伝票が要るのは、既存の設計がそうなっているからです
 * （1 組のお客さんが何度も注文するので、注文は必ず伝票にぶら下がる）。
 * その面倒をテスト側に散らかさないよう、ここにまとめています。
 */
@Component
public class InventoryTestFixture {

    /**
     * 注文番号の採番。
     *
     * <p>{@code orders} は（営業日, 注文番号）に一意制約が張ってあります。
     * 実運用では日ごとに 1 から振り直しますが、テストでは複数のテストが
     * 同じ日付を使うので、<b>ぶつからないよう通しで増やす</b>だけにしています。
     */
    private static final java.util.concurrent.atomic.AtomicInteger ORDER_NUMBER =
            new java.util.concurrent.atomic.AtomicInteger(1000);

    private final CategoryRepository categories;
    private final MenuItemRepository menuItems;
    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;
    private final OrderRepository orders;
    private final ShopSettingService shopSettings;
    private final PurchaseService purchaseService;

    public InventoryTestFixture(CategoryRepository categories,
                                MenuItemRepository menuItems,
                                DiningTableRepository tables,
                                TableSessionRepository sessions,
                                OrderRepository orders,
                                ShopSettingService shopSettings,
                                PurchaseService purchaseService) {
        this.categories = categories;
        this.menuItems = menuItems;
        this.tables = tables;
        this.sessions = sessions;
        this.orders = orders;
        this.shopSettings = shopSettings;
        this.purchaseService = purchaseService;
    }

    /** 商品を 1 つ作る。カテゴリは使い回す。 */
    @Transactional
    public MenuItem createMenuItem(String name, int price) {
        Category category = categories.findAll().stream().findFirst()
                .orElseGet(() -> categories.save(new Category("テスト用カテゴリ", 0)));
        return menuItems.save(new MenuItem(category, name, price));
    }

    /**
     * 指定した営業日に、その商品を売る。
     *
     * <p>状態は {@code RECEIVED} から順に遷移させます。
     * 直接代入せず本来の経路を通すのは、<b>その状態に本当になれるか</b>を
     * ついでに確かめるためです。
     */
    @Transactional
    public void placeOrder(MenuItem item, LocalDate businessDate, int quantity, OrderStatus target) {
        ShopSetting setting = shopSettings.currentReadOnly();

        DiningTable table = tables.findAll().stream().findFirst()
                .orElseGet(() -> tables.save(new DiningTable("テスト卓", 4, 0)));
        TableSession session = sessions.save(
                new TableSession(table, businessDate, 2, setting));

        Order order = new Order(businessDate, ORDER_NUMBER.incrementAndGet(),
                setting.getTaxRatePercent());
        order.setSession(session);
        order.addLine(new OrderLine(item.getId(), item.getName(), item.getPrice(), quantity, 5));
        order.recalculate();

        advanceTo(order, target);
        orders.save(order);
    }

    /** 目的の状態まで、許されている道筋をたどる。 */
    private void advanceTo(Order order, OrderStatus target) {
        if (target == OrderStatus.RECEIVED) {
            return;
        }
        List<OrderStatus> path = switch (target) {
            case COOKING -> List.of(OrderStatus.COOKING);
            case READY -> List.of(OrderStatus.COOKING, OrderStatus.READY);
            case COMPLETED -> List.of(OrderStatus.COOKING, OrderStatus.READY, OrderStatus.COMPLETED);
            case CANCELED -> List.of(OrderStatus.CANCELED);
            default -> List.of();
        };
        for (OrderStatus step : path) {
            order.changeStatus(step, "テスト");
        }
    }

    /**
     * その食材を仕入れた記録を 1 本作る。
     *
     * @param amount   その行の金額（円・税込）
     * @param stockQty 在庫に積む量
     */
    @Transactional
    public void recordPurchase(Ingredient ingredient, int amount, BigDecimal stockQty, int taxRatePercent) {
        LocalDate today = LocalDate.now();
        PurchaseDraft draft = new PurchaseDraft(
                today, today, "テスト八百屋", amount, PaymentMethod.CASH,
                null, EvidenceType.NOT_QUALIFIED, null, null, null, false,
                List.of(new PurchaseDraft.LineDraft(
                        "テスト仕入-" + System.nanoTime(), BigDecimal.ONE, amount,
                        taxRatePercent, null, PurchaseCategory.FOOD,
                        ingredient.getId(), stockQty, false)));
        purchaseService.record(draft, null);
    }
}
