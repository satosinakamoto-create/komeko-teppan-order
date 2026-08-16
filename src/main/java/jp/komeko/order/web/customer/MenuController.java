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
        model.addAttribute("accepting", setting.isOrderAcceptable(now));
        model.addAttribute("rejectReason", setting.orderRejectReason(now));
        model.addAttribute("src", src);
        return "customer/menu";
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
