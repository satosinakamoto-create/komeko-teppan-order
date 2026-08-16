package jp.komeko.order.web.customer;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.cart.TableContext;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.domain.TableSession;
import jp.komeko.order.service.ShopSettingService;
import jp.komeko.order.service.TableService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 卓の QR コードを読んだお客さんの入口。
 *
 * <p>QR には {@code https://店のURL/t/{ランダムなトークン}} が入っています。
 * ここでやることは 2 つだけです。
 *
 * <ol>
 *   <li>トークンから卓を特定し、ブラウザのセッションに覚えさせる（{@link TableContext}）</li>
 *   <li>その卓の伝票が開いていなければ、人数を聞いて開く</li>
 * </ol>
 *
 * <p>これ以降はふつうのメニュー URL（{@code /}、{@code /items/12}）を回っても
 * 「3番テーブルの人だ」と分かるので、注文が正しい伝票に入ります。
 */
@Controller
public class TableEntryController {

    private final TableService tableService;
    private final ShopSettingService shopSettingService;
    private final TableContext tableContext;
    private final Cart cart;

    public TableEntryController(TableService tableService,
                                ShopSettingService shopSettingService,
                                TableContext tableContext,
                                Cart cart) {
        this.tableService = tableService;
        this.shopSettingService = shopSettingService;
        this.tableContext = tableContext;
        this.cart = cart;
    }

    /**
     * QR の飛び先。
     *
     * <p>すでに伝票が開いていればメニューへ、開いていなければ人数の確認画面へ進みます。
     */
    @GetMapping("/t/{accessToken}")
    public String enter(@PathVariable String accessToken, Model model) {
        DiningTable table = tableService.getByAccessToken(accessToken);

        // 別の卓の QR を読み直したときは、前の卓のカートを引きずらないようにする
        if (tableContext.isBound() && !table.getId().equals(tableContext.getTableId())) {
            cart.clear();
        }
        tableContext.bind(table.getId(), table.getName(), accessToken);

        Optional<TableSession> session = tableService.currentSession(table.getId());
        if (session.isPresent()) {
            return "redirect:/";
        }

        ShopSetting setting = shopSettingService.currentReadOnly();
        LocalDateTime now = LocalDateTime.now();

        model.addAttribute("table", table);
        model.addAttribute("accepting", setting.isOrderAcceptable(now));
        model.addAttribute("rejectReason", setting.orderRejectReason(now));
        return "customer/table-start";
    }

    /**
     * 人数を確定して伝票を開く（ご案内）。
     *
     * <p>人数を聞くのはテーブルチャージの計算に必要だからです。
     * お客さんの申告なので、実際と違っていればスタッフがホール画面から直せます。
     */
    @PostMapping("/t/{accessToken}/start")
    public String start(@PathVariable String accessToken,
                        @RequestParam int guestCount) {
        DiningTable table = tableService.getByAccessToken(accessToken);
        tableContext.bind(table.getId(), table.getName(), accessToken);
        tableService.openSession(table.getId(), Math.max(1, Math.min(guestCount, 20)));
        return "redirect:/";
    }
}
