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
        MenuItem item = menuService.itemWithOptions(id);
        ShopSetting setting = shopSettingService.currentReadOnly();

        model.addAttribute("item", item);
        model.addAttribute("accepting", setting.isOrderAcceptable(LocalDateTime.now()));
        return "customer/item";
    }
}
