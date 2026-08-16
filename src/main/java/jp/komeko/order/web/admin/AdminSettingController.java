package jp.komeko.order.web.admin;

import jakarta.validation.Valid;
import jp.komeko.order.domain.ShopSetting;
import jp.komeko.order.service.ShopSettingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.beans.PropertyEditorSupport;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 店舗設定の画面（管理者のみ）。
 *
 * <p><b>コントローラの役割</b><br>
 * 「HTTP のリクエストを受け取り、サービスに仕事を頼み、どの画面を返すか決める」
 * のがコントローラの仕事です。業務ルール（何を保存してよいか、いつ受付を止めるか）は
 * {@link ShopSettingService} 側にあり、ここには書きません。
 * こうしておくと、あとから同じ処理をスマホアプリ用の API から呼びたくなったときに、
 * サービスをそのまま再利用できます。
 *
 * <p><b>PRG（Post-Redirect-Get）について</b><br>
 * 保存に成功したら必ず {@code redirect:} で GET に戻します。
 * POST の結果をそのまま HTML で返すと、ブラウザの再読み込みで
 * 「もう一度送信しますか？」と聞かれ、二重登録の原因になるためです。
 * 逆に<b>入力エラーのときはリダイレクトしません</b>。
 * 入力途中の値とエラー箇所をそのまま画面に残したいからです
 * （PRG は「成功したときに二重送信を防ぐ」ための仕組みであって、
 * 入力し直しの画面まで往復させる必要はありません）。
 */
@Controller
@RequestMapping("/admin/settings")
public class AdminSettingController {

    /** {@code <input type="time">} がブラウザから送ってくる形式。 */
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ShopSettingService shopSettingService;

    /** コンストラクタインジェクション。Spring が自動でサービスを渡してくれます。 */
    public AdminSettingController(ShopSettingService shopSettingService) {
        this.shopSettingService = shopSettingService;
    }

    // ========================================================================
    //  入力値の変換ルール（LocalTime 対応）
    // ========================================================================

    /**
     * このコントローラ専用の「文字列 → Java の型」変換ルールを登録する。
     *
     * <p><b>なぜ必要か</b><br>
     * 開店時刻などは {@link LocalTime} 型ですが、ブラウザから届くのはただの文字列です。
     * Spring の既定の変換は「その環境のロケールに合わせた時刻表記」を前提にしているため、
     * 環境によっては {@code "11:00"} を解釈できず 400 エラーになることがあります。
     * そこで {@code <input type="time">} が必ず送ってくる {@code HH:mm} 形式を
     * 明示的に登録して、どの環境でも同じ結果になるようにします。
     *
     * <p><b>{@code @InitBinder} と {@code WebDataBinder} のどちらを選んだか</b><br>
     * 実はこの 2 つは対立する選択肢ではありません。
     * {@code @InitBinder} は「バインド（文字列→オブジェクトへの詰め替え）の直前に
     * Spring が呼んでくれるフック」で、その引数として渡ってくるのが
     * {@link WebDataBinder}（バインドの設定係）です。
     * <b>今回は「@InitBinder メソッドを作り、その中で WebDataBinder に変換ルールを登録する」</b>
     * という形にしました。理由は次の 2 点です。
     * <ol>
     *   <li>{@link ShopSetting} は他の担当者も触るファイルなので、
     *       {@code @DateTimeFormat} を書き足す（＝ドメインを編集する）方法は取れない</li>
     *   <li>グローバルな設定クラスに登録すると全画面に影響する。
     *       この画面だけの都合なので、影響範囲をこのコントローラ内に閉じ込めたい</li>
     * </ol>
     *
     * <p>ついでに {@code setDisallowedFields} で id と updatedAt を受け付けないようにしています。
     * リクエストに {@code id=99} を紛れ込ませるだけで別レコードを触られる、
     * といった攻撃（マスアサインメント）を防ぐためのお約束です。
     */
    @InitBinder
    void initBinder(WebDataBinder binder) {
        binder.setDisallowedFields("id", "updatedAt");
        binder.registerCustomEditor(LocalTime.class, new LocalTimeEditor());
    }

    /**
     * 「HH:mm の文字列」と「LocalTime」を相互変換する小さな部品。
     *
     * <p>{@link PropertyEditorSupport} を継承して
     * {@code setAsText}（画面 → Java）と {@code getAsText}（Java → 画面）を書くだけです。
     * getAsText も必ず実装します。これが無いと {@code th:field} が
     * {@code "11:00:00"} のような秒付きの文字列を出してしまい、
     * {@code <input type="time">} が値を表示できないことがあるためです。
     */
    private static class LocalTimeEditor extends PropertyEditorSupport {

        @Override
        public void setAsText(String text) {
            if (text == null || text.isBlank()) {
                // 空欄はここでは null にしておき、日本語のエラーは save() 側で付ける
                setValue(null);
                return;
            }
            try {
                // LocalTime.parse は "11:00" も "11:00:00" も受け付けてくれる
                setValue(LocalTime.parse(text.trim()));
            } catch (DateTimeParseException e) {
                // ここで投げた例外は Spring が「型変換エラー」として BindingResult に載せてくれる
                throw new IllegalArgumentException("時刻は HH:mm の形式で入力してください（例: 11:00）", e);
            }
        }

        @Override
        public String getAsText() {
            LocalTime value = (LocalTime) getValue();
            return value == null ? "" : value.format(HH_MM);
        }
    }

    // ========================================================================
    //  画面
    // ========================================================================

    /**
     * 店舗設定フォームを表示する。
     *
     * <p>{@code activeNav} はヘッダーのどのメニューを強調するかの目印で、
     * {@code layout/staff.html} が参照しています。
     * 店舗名などが入った {@code shop} は {@code GlobalModelAttributes}
     * （{@code @ControllerAdvice}）が全画面に自動で入れてくれるので、ここでは不要です。
     */
    @GetMapping
    public String form(Model model) {
        model.addAttribute("activeNav", "admin");
        model.addAttribute("form", shopSettingService.current());
        return "admin/settings";
    }

    /**
     * 店舗設定を保存する。
     *
     * <p><b>なぜエンティティ {@link ShopSetting} を直接フォームにバインドしてよいのか</b><br>
     * 普通、エンティティを画面から直接バインドするのは危険です（マスアサインメント）。
     * しかしこの画面に限っては次の条件がそろっているため、安全に使えます。
     * <ul>
     *   <li>{@link ShopSettingService#save(ShopSetting)} が
     *       <b>必要なフィールドだけを既存レコードに写す</b>実装になっている。
     *       つまり受け取ったインスタンスがそのまま DB に書かれるのではなく、
     *       「どの項目を反映するか」はサービス側が握っている</li>
     *   <li>設定は 1 行しか無く（id 固定 1）、他人のデータを書き換える余地が無い</li>
     *   <li>上の {@code @InitBinder} で id と updatedAt のバインドを禁止している</li>
     * </ul>
     * おかげでフォーム専用クラスを 1 つ増やさずに済み、
     * 「ShopSetting に項目を足したら画面にも足す」という対応関係が分かりやすくなります。
     *
     * <p><b>ここが落とし穴</b><br>
     * バインド対象は毎回「新品の ShopSetting」です（Spring が no-arg コンストラクタで作る）。
     * そのため<b>画面に置き忘れた項目は初期値のまま save() に渡り、DB の値を上書きします</b>。
     * 例えば受付停止中に、受付フラグの入力欄が無い状態で保存すると、
     * 初期値 true が書き込まれて勝手に受付が再開されてしまいます。
     * settings.html には save() が写す 12 項目すべての入力欄を必ず置いてください。
     */
    @PostMapping
    public String save(@Valid @ModelAttribute("form") ShopSetting form,
                       BindingResult bindingResult,
                       Model model,
                       RedirectAttributes redirectAttributes) {

        // アノテーションだけでは表現しづらい「項目どうしの関係」はここで検査する
        validateTimes(form, bindingResult);

        if (bindingResult.hasErrors()) {
            // エラー時はリダイレクトせずに描き直す（入力値とエラー箇所を保つため）。
            // form と BindingResult は Spring が自動でモデルに入れてくれるので追加不要。
            model.addAttribute("activeNav", "admin");
            return "admin/settings";
        }

        shopSettingService.save(form);
        redirectAttributes.addFlashAttribute("flashSuccess", "店舗設定を保存しました");
        return "redirect:/admin/settings";
    }

    /**
     * 注文受付の一時停止／再開をワンタップで切り替える。
     *
     * <p>混雑して手が回らなくなったときの「非常ブレーキ」なので、
     * 設定フォームの保存とは別のボタン・別の URL にしています。
     * 他の項目を触らずに、これだけを即座に切り替えられることが大事です。
     */
    @PostMapping("/toggle-accepting")
    public String toggleAccepting(RedirectAttributes redirectAttributes) {
        boolean accepting = shopSettingService.toggleAccepting();
        redirectAttributes.addFlashAttribute("flashSuccess",
                accepting ? "注文の受付を再開しました" : "注文の受付を一時停止しました");
        return "redirect:/admin/settings";
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /**
     * 時刻まわりの整合性チェック。
     *
     * <p>{@code rejectValue} で登録したエラーは、テンプレート側の
     * {@code th:errors="*{openTime}"} にそのまま表示されます。
     */
    private void validateTimes(ShopSetting form, BindingResult bindingResult) {
        // 空欄は型変換の段階で null になっている（LocalTimeEditor 参照）。
        // DB の列は NOT NULL なので、ここで日本語のエラーにして弾く。
        if (form.getOpenTime() == null && !bindingResult.hasFieldErrors("openTime")) {
            bindingResult.rejectValue("openTime", "required", "開店時刻を入力してください");
        }
        if (form.getCloseTime() == null && !bindingResult.hasFieldErrors("closeTime")) {
            bindingResult.rejectValue("closeTime", "required", "閉店時刻を入力してください");
        }
        if (form.getLastOrderTime() == null && !bindingResult.hasFieldErrors("lastOrderTime")) {
            bindingResult.rejectValue("lastOrderTime", "required", "ラストオーダー時刻を入力してください");
        }

        // 開店より前にラストオーダーが来ていると、一日中「受付時間外」になってしまう。
        // 保存できてしまうと原因に気づきにくいので、入口で止める。
        if (form.getOpenTime() != null && form.getLastOrderTime() != null
                && form.getOpenTime().isAfter(form.getLastOrderTime())) {
            bindingResult.rejectValue("lastOrderTime", "order",
                    "ラストオーダーは開店時刻より後にしてください（このままだと一日中受付できません）");
        }
    }
}
