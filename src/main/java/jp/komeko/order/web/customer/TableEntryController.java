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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;
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
     *
     * <p><b>{@code int} ではなく {@code String} で受けている理由（実際に起きた不具合）</b><br>
     * 以前は {@code @RequestParam int guestCount} でした。
     * ところが「9名以上のとき」の欄を<b>空のまま「決定」を押す</b>と、
     * ブラウザは {@code guestCount=}（空文字）を送ります。
     * 空文字は数値に変換できないため Spring が 400 を返し、
     * お客さまの画面には「400 Bad Request」とだけ出ていました。
     *
     * <p>ここは QR を読んだ直後の、いちばん最初の操作です。
     * 意味の分からないエラーが出れば、お客さまは店員を呼ぶしかありません。
     * <b>入力の間違いは「エラー」ではなく「案内」で返す</b>のが正しい扱いなので、
     * いったん文字列で受け取って自分で解釈し、
     * 数値として読めなければ元の画面へ案内を出して戻します。
     *
     * <p>{@code required = false} も付けています。
     * 何かの拍子にパラメータ自体が届かなかったときも、400 ではなく案内で返すためです。
     */
    @PostMapping("/t/{accessToken}/start")
    public String start(@PathVariable String accessToken,
                        @RequestParam(required = false) String guestCount,
                        RedirectAttributes redirectAttributes) {
        DiningTable table = tableService.getByAccessToken(accessToken);
        tableContext.bind(table.getId(), table.getName(), accessToken);

        Integer parsed = parseGuestCount(guestCount);
        if (parsed == null) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("人数をお選びください（9名以上のときは、数字を入れてから「決定」を押してください）"));
            return "redirect:/t/" + accessToken;
        }

        tableService.openSession(table.getId(), Math.max(1, Math.min(parsed, 20)));
        return "redirect:/";
    }

    /**
     * 送られてきた人数を数値にする。数値として読めなければ {@code null}。
     *
     * <p>この画面には「1〜8名のボタン」と「9名以上の入力欄」があり、
     * どちらも同じ {@code guestCount} という名前です。
     * ボタンを押すと<b>両方が送られる</b>ため、Spring は
     * {@code "2,"} のようにカンマで繋いだ 1 つの文字列として渡してきます。
     * そこで<b>先頭から順に見て、最初に数値として読めたものを採用</b>します。
     */
    private Integer parseGuestCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String part : raw.split(",")) {
            try {
                return Integer.valueOf(part.trim());
            } catch (NumberFormatException ignored) {
                // 空欄など、数値でないものは飛ばして次を見る
            }
        }
        return null;
    }
}
