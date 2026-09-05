package jp.komeko.order.web.customer;

import jp.komeko.order.cart.TableContext;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.service.MenuService;
import jp.komeko.order.service.ShopSettingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * お客さんが最初に開く画面（QR コードの飛び先）。
 *
 * <p><b>{@code @Controller} と {@code @RestController} の違い</b><br>
 * {@code @Controller} のメソッドが返す文字列は「テンプレートの名前」として扱われ、
 * その HTML が描画されて返ります。
 * 一方 {@code @RestController} は戻り値をそのまま JSON にして返します。
 * 画面を返したいのでこちらは {@code @Controller} です。
 */
@Controller
public class MenuController {

    private final MenuService menuService;
    private final ShopSettingService shopSettingService;
    private final TableContext tableContext;

    public MenuController(MenuService menuService,
                          ShopSettingService shopSettingService,
                          TableContext tableContext) {
        this.menuService = menuService;
        this.shopSettingService = shopSettingService;
        this.tableContext = tableContext;
    }

    /**
     * メニュー一覧。
     *
     * <p>卓の QR を読んでいない状態（＝どの席か分からない状態）では
     * 注文できないので、案内ページを出します。
     * ブックマークや検索から直接来た人がここに来ます。
     */
    /**
     * 飲み物の大分類の名前。
     *
     * <p>設計（暗01 / 暗02）では、上の帯が<b>お食事 / ドリンク</b>の 2 つに分かれ、
     * その下の帯にカテゴリ（お好み焼き・たこ焼き・ビール・サワー…）が並びます。
     * ところがカテゴリが持っている大カテゴリ名は
     * 粉もの／鉄板料理／一品料理／ドリンク の 4 つで、1 段ずれています。
     *
     * <p>列を足して作り直すこともできますが、
     * <b>お客さまから見た区別は「食べ物か、飲み物か」の 1 本だけ</b>なので、
     * 「ドリンク」という大カテゴリかどうかで分ければ足ります。
     * 実データもそう入っています（DataSeeder の groupNameFor）。
     *
     * <p>店長がこの名前を変えるとドリンクのタブが空になりますが、
     * そのときは画面に「準備中です」と出るだけで、注文は止まりません。
     */
    private static final String DRINK_SECTION = "ドリンク";

    /**
     * サービスの大分類の名前。
     *
     * <p>お水・おしぼり・取り皿などは ¥0 の商品として登録してありますが、
     * <b>メニューには出しません</b>。専用の画面（{@code /service}）があるからです。
     * ここで外さないと、お水がお好み焼きの隣に ¥0 で並びます。
     */
    private static final String SERVICE_SECTION = ServiceController.SERVICE_CATEGORY;

    @GetMapping({"/", "/menu"})
    public String menu(@RequestParam(required = false) String src,
                       @RequestParam(required = false) String tab,
                       Model model) {
        if (!tableContext.isBound()) {
            return "customer/no-table";
        }
        Map<Category, List<MenuItem>> menu = menuService.customerMenu();
        ShopSetting setting = shopSettingService.currentReadOnly();
        LocalDateTime now = LocalDateTime.now();

        boolean drink = DRINK_SECTION.equals(tab);
        model.addAttribute("menu", menu);
        model.addAttribute("tabs", categoriesOf(menu, drink));
        model.addAttribute("drinkSection", drink);
        model.addAttribute("accepting", setting.isOrderAcceptable(now));
        model.addAttribute("rejectReason", setting.orderRejectReason(now));
        model.addAttribute("src", src);
        return "customer/menu";
    }

    /**
     * いま見ている大分類（お食事／ドリンク）のカテゴリだけを取り出す。
     *
     * <p>戻り値の形は{@code groupIntoTabs} と同じ「タブ名 → カテゴリ → 商品」ですが、
     * <b>タブ 1 つにつきカテゴリ 1 つ</b>にしてあります。
     * 画面の帯に並ぶのがカテゴリそのものになり、
     * 横に流れる面（パネル）もカテゴリ単位で切り替わります。
     *
     * <p>入れ物を変えずに中身の粒度だけ変えているのは、
     * 面の切り替えとスクロール位置の記憶（customer/menu.html の script）が
     * 「タブと面が 1 対 1」という前提で書かれているからです。
     * その前提を保ったまま粒度だけ下げれば、あちらは 1 行も直らずに済みます。
     */
    private Map<String, Map<Category, List<MenuItem>>> categoriesOf(
            Map<Category, List<MenuItem>> menu, boolean drink) {

        Map<String, Map<Category, List<MenuItem>>> tabs = new LinkedHashMap<>();
        for (Map.Entry<Category, List<MenuItem>> entry : menu.entrySet()) {
            String section = entry.getKey().getTabName();
            if (SERVICE_SECTION.equals(section)) {
                continue;
            }
            if (DRINK_SECTION.equals(section) != drink) {
                continue;
            }
            Map<Category, List<MenuItem>> one = new LinkedHashMap<>();
            one.put(entry.getKey(), entry.getValue());
            tabs.put(entry.getKey().getName(), one);
        }
        return tabs;
    }

    /**
     * 商品詳細（オプションを選んでカートに入れる画面）。
     *
     * <p>{@code @PathVariable} は URL の一部を引数として受け取る仕組みです。
     * {@code /items/12} なら {@code id = 12} になります。
     */
    @GetMapping("/items/{id}")
    public String item(@PathVariable Long id, Model model) {
        // ★ 一覧（/menu）にある 2 つの守りが、ここには無かった（2026-09-04 に追加）★
        //
        //   一覧は「卓の QR を読んだか」と「掲載中か」の 2 つで絞っている。
        //   ところが詳細は menuService.itemWithOptions(id) を素で呼ぶだけで、
        //   その裏の findByIdWithOptions は where m.id = :id しか見ていない。
        //   つまり URL の数字を 42 → 43 と変えるだけで、
        //   一覧に出していない商品まで読めていた。
        //
        //   困るのは「まだ出していない商品」を隠したときで、
        //   店主は一覧から消えたのを見て隠せたと思うが、詳細では読める。
        //   注文ボタンが出ないだけで警告も無いので、
        //   営業中の普通の商品ページに見えてしまう。
        //
        //   新しい仕組みは要らない。一覧が既にやっていることを、ここでもやる。
        if (!tableContext.isBound()) {
            return "customer/no-table";
        }
        MenuItem item = menuService.itemWithOptions(id);
        // カテゴリごと非表示にしている場合もあるので、商品とカテゴリの両方を見る
        // （findVisibleForCustomer の where 句と同じ条件にそろえてある）
        if (!item.isVisible() || !item.getCategory().isVisible()) {
            // 商品名は渡さない。隠している品の名前を出したら、隠した意味が無い
            return "customer/no-item";
        }
        ShopSetting setting = shopSettingService.currentReadOnly();

        model.addAttribute("item", item);
        model.addAttribute("accepting", setting.isOrderAcceptable(LocalDateTime.now()));
        return "customer/item";
    }
}
