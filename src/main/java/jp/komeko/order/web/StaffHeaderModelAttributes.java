package jp.komeko.order.web;

import jakarta.servlet.http.HttpServletRequest;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.service.OrderService;
import jp.komeko.order.service.ShopSettingService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 店側の画面の上に出る帯（ヘッダー）に必要な値を配るクラス。
 *
 * <p>出すのは 3 つ。<b>営業中に「いま店がどうなっているか」を、
 * どの画面からでも顔を上げずに確かめられる</b>ようにするためのものです。
 *
 * <ul>
 *   <li>受付の状態 … 注文を受け付けているか</li>
 *   <li>未提供の件数 … まだ出していない注文が何件あるか</li>
 *   <li>営業日 … いまどの営業日か（深夜は前日のまま）</li>
 * </ul>
 *
 * <p><b>なぜ {@link GlobalModelAttributes} に足さないのか</b><br>
 * あちらは<b>お客さまの画面にも効きます</b>。未提供の件数を数えるには
 * DB に問い合わせが要るので、そこに置くと注文画面を開くたびに
 * お客さまには関係のないクエリが 1 本増えます。
 * {@code basePackages} で店側だけに絞るこの形は、
 * {@code CustomerModelAttributes} が「厨房や管理でカートのセッションを
 * 作らせない」ためにやっているのと同じ考え方です。
 *
 * <p><b>{@code /accountant} を含めていない理由</b><br>
 * 税理士の画面は別のレイアウト（{@code layout/accountant}）で、この帯を持ちません。
 * 帯に出るのは営業中の状態——受付・未提供・営業日——で、
 * 税理士の仕事（月次の帳簿）とは何の関係もありません。
 * 渡す範囲は狭いほうがよい、というのは
 * {@code docs\仕様.md} 7.2 に書いた線引きと同じです。
 */
@ControllerAdvice(basePackages = {
        "jp.komeko.order.web.admin",
        "jp.komeko.order.web.kitchen",
        "jp.komeko.order.web.hall",
        "jp.komeko.order.inventory.web",
})
public class StaffHeaderModelAttributes {

    private final ShopSettingService shopSettingService;
    private final OrderService orderService;

    public StaffHeaderModelAttributes(ShopSettingService shopSettingService,
                                      OrderService orderService) {
        this.shopSettingService = shopSettingService;
        this.orderService = orderService;
    }

    /**
     * いま注文を受け付けているか。
     *
     * <p><b>{@code shop.acceptingOrders} をそのまま使ってはいけません。</b>
     * あれは店長が手で倒した非常ブレーキの状態だけで、営業時間もラストオーダーも見ません。
     * ブレーキを引いていなくてもラストオーダーを過ぎていれば受け付けられないので、
     * フラグだけを見て「注文を受付中」と出すと、止まっていることに気づけません。
     * 厨房ボードが {@code accepting} を作っているのと同じ判定です。
     */
    @ModelAttribute("headerAccepting")
    public Boolean headerAccepting(HttpServletRequest request) {
        if (skip(request)) {
            return null;
        }
        ShopSetting setting = shopSettingService.currentReadOnly();
        return setting.isOrderAcceptable(LocalDateTime.now());
    }

    /**
     * まだ提供していない注文の件数。厨房ボードの「未処理 N 件」と同じ数字。
     *
     * <p>0 件なら帯には出しません（0 と書いてあるより、無いほうが読み取りが速い）。
     */
    @ModelAttribute("headerPendingCount")
    public Integer headerPendingCount(HttpServletRequest request) {
        if (skip(request)) {
            return null;
        }
        return orderService.pendingCount();
    }

    /** いまの営業日。深夜 0〜5 時は前日のまま（{@code businessDayCutoverHour}）。 */
    @ModelAttribute("headerBusinessDate")
    public LocalDate headerBusinessDate(HttpServletRequest request) {
        if (skip(request)) {
            return null;
        }
        return shopSettingService.currentBusinessDate();
    }

    /**
     * 画面を描かないリクエストでは、値を作らない。
     *
     * <p>厨房とホールのパッケージには通知用の {@code /api/stream/...}（SSE）が同居しています。
     * {@code @ModelAttribute} は同じパッケージのコントローラなら全部で走るので、
     * 何もしないと<b>通知の接続が張られるたびに件数のクエリが 1 本増えます</b>。
     * SSE は画面を描かないので、この値は使われないまま捨てられます。
     *
     * <p>印刷用の QR ページ（{@code /admin/qr/print}）も帯を持たないレイアウトですが、
     * こちらは 1 リクエストで終わるので、わざわざ書き分けていません。
     */
    private boolean skip(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/api/");
    }
}
