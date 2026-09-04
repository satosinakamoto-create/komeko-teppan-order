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
    @GetMapping({"/", "/menu"})
    public String menu(@RequestParam(required = false) String src, Model model) {
        if (!tableContext.isBound()) {
            return "customer/no-table";
        }
        Map<Category, List<MenuItem>> menu = menuService.customerMenu();
        ShopSetting setting = shopSettingService.currentReadOnly();
        LocalDateTime now = LocalDateTime.now();

        model.addAttribute("menu", menu);
        model.addAttribute("tabs", groupIntoTabs(menu));
        model.addAttribute("accepting", setting.isOrderAcceptable(now));
        model.addAttribute("rejectReason", setting.orderRejectReason(now));
        model.addAttribute("src", src);
        return "customer/menu";
    }

    /**
     * カテゴリを大カテゴリ（タブ）ごとにまとめ直す。
     *
     * <p>このお店はカテゴリが 14 個あります。そのまま横一列のタブにすると
     * 端まで探しに行けないので、{@code Category#getTabName()} が同じものを
     * 1 つのタブにまとめ、タブの中では従来どおりカテゴリごとの見出しを出します。
     *
     * <p><b>{@link java.util.LinkedHashMap} を使う理由</b><br>
     * ふつうの {@code HashMap} は<b>入れた順番を覚えていません</b>。
     * それだとタブの並びが起動のたびに変わりかねず、
     * 「昨日はここにあったのに」という混乱のもとになります。
     * {@code LinkedHashMap} は入れた順を保つので、
     * 渡された時点の並び（カテゴリの並び順）がそのままタブの並びになります。
     *
     * @param menu カテゴリ順に並んだメニュー（{@code MenuService} が並べ替え済み）
     * @return タブ名 → そのタブに属するカテゴリとその商品
     */
    private Map<String, Map<Category, List<MenuItem>>> groupIntoTabs(
            Map<Category, List<MenuItem>> menu) {

        Map<String, Map<Category, List<MenuItem>>> tabs = new LinkedHashMap<>();
        for (Map.Entry<Category, List<MenuItem>> entry : menu.entrySet()) {
            // computeIfAbsent は「無ければ作って入れる、あればそれを返す」。
            // if (get == null) { put } と書くのと同じことを 1 行で書ける
            tabs.computeIfAbsent(entry.getKey().getTabName(), name -> new LinkedHashMap<>())
                    .put(entry.getKey(), entry.getValue());
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
