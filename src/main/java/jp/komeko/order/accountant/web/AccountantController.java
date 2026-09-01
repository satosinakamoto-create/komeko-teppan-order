package jp.komeko.order.accountant.web;

import jp.komeko.order.accountant.domain.JournalRule;
import jp.komeko.order.accountant.repository.JournalRuleRepository;
import jp.komeko.order.accountant.service.AccountantService;
import jp.komeko.order.accountant.service.JournalExportService;
import jp.komeko.order.inventory.domain.Purchase;
import jp.komeko.order.security.StaffUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;

/**
 * 税理士の画面（{@code /accountant}）。
 *
 * <p><b>誰のための画面か</b><br>
 * 顧問税理士が月に一度、顧問先の帳簿を見るための画面です。
 * 店の運営（卓・QR・商品・スタッフ）は一切見えません。逆に、
 * 店側の画面には無い「消費税の集計」「仕訳の書き出し」がここにあります。
 *
 * <p><b>画面の並びは、月次の作業の順番</b>
 * <pre>
 *   ① 今月のまとめ    … 売上と仕入れが一目で分かる（＝資料を受け取った状態）
 *   ② 消費税の集計    … 税率×控除区分。申告書に写す数字そのもの
 *   ③ 証憑の確認      … 1 枚ずつ画像と突き合わせ、確認の記録を残す
 *   ④ 仕訳の書き出し  … 会計ソフトに取り込む CSV
 *   ⑤ 対応表の設定    … 費目→勘定科目・税区分（③④の前提）
 * </pre>
 *
 * <p><b>書けるのは「確認した」だけ</b><br>
 * 税理士は店の数字を直せません。外部の人が帳簿を書き換えられると、
 * 誰が作った数字なのかが曖昧になるためです。
 * 直すべき点は申し送りで店に伝え、店が直します。
 *
 * <p><b>接続元 IP の制限には入れていません。</b>
 * 店内 LAN からしか開けない設定（{@code StaffZoneIpFilter}）に
 * {@code /accountant} を加えると、<b>税理士が自分の事務所から見られなくなります</b>。
 * この画面だけは外から使われる前提です。
 */
@Controller
@RequestMapping("/accountant")
public class AccountantController {

    private final AccountantService accountantService;
    private final JournalExportService journalExport;
    private final JournalRuleRepository rules;

    public AccountantController(AccountantService accountantService,
                                JournalExportService journalExport,
                                JournalRuleRepository rules) {
        this.accountantService = accountantService;
        this.journalExport = journalExport;
        this.rules = rules;
    }

    /** どの画面でも使う「対象の月」と件数を、まとめてモデルに載せる。 */
    private YearMonth putCommon(Model model, String month) {
        YearMonth target = parseMonth(month);
        model.addAttribute("month", target);
        model.addAttribute("prevMonth", target.minusMonths(1));
        model.addAttribute("nextMonth", target.plusMonths(1));
        model.addAttribute("awaitingCount", accountantService.awaitingCount(target));
        model.addAttribute("attentionCount", accountantService.attentions(target).size());
        return target;
    }

    // ========================================================================
    //  ① 今月のまとめ
    // ========================================================================

    @GetMapping
    public String index(@RequestParam(required = false) String month, Model model) {
        YearMonth target = putCommon(model, month);
        model.addAttribute("summary", accountantService.summarize(target));
        model.addAttribute("taxRows", accountantService.taxBreakdown(target));
        model.addAttribute("attentions", accountantService.attentions(target));
        return "accountant/index";
    }

    // ========================================================================
    //  ② 消費税の集計
    // ========================================================================

    @GetMapping("/tax")
    public String tax(@RequestParam(required = false) String month, Model model) {
        YearMonth target = putCommon(model, month);
        model.addAttribute("taxRows", accountantService.taxBreakdown(target));
        model.addAttribute("summary", accountantService.summarize(target));
        return "accountant/tax";
    }

    // ========================================================================
    //  ③ 証憑の確認
    // ========================================================================

    @GetMapping("/evidence")
    public String evidence(@RequestParam(required = false) String month, Model model) {
        YearMonth target = putCommon(model, month);
        model.addAttribute("queue", accountantService.workQueue(target));
        model.addAttribute("attentions", accountantService.attentions(target));
        model.addAttribute("deleted", accountantService.deleted(target));
        return "accountant/evidence";
    }

    /** 1 枚の証憑を、画像と入力値を並べて確かめる。 */
    @GetMapping("/evidence/{id}")
    public String evidenceDetail(@PathVariable Long id,
                                 @RequestParam(required = false) String month,
                                 Model model) {
        Purchase purchase = accountantService.find(id);
        if (purchase == null) {
            return "redirect:/accountant/evidence";
        }
        putCommon(model, month);
        model.addAttribute("purchase", purchase);
        return "accountant/evidence-detail";
    }

    /**
     * 「確認した」を記録する。<b>税理士が書ける唯一の操作</b>。
     */
    @PostMapping("/evidence/{id}/checked")
    public String markChecked(@PathVariable Long id,
                              @RequestParam(required = false) String note,
                              @RequestParam(required = false) String month,
                              @AuthenticationPrincipal StaffUserDetails user,
                              RedirectAttributes redirect) {
        accountantService.markChecked(id, user != null ? user.getId() : null, note);
        redirect.addFlashAttribute("flashSuccess", "確認したことを記録しました");
        return "redirect:/accountant/evidence" + (month != null ? "?month=" + month : "");
    }

    // ========================================================================
    //  ④ 仕訳の書き出し
    // ========================================================================

    @GetMapping("/journal")
    public String journal(@RequestParam(required = false) String month, Model model) {
        YearMonth target = putCommon(model, month);
        model.addAttribute("rowCount", journalExport.countRows(target));
        model.addAttribute("missingRules", journalExport.missingRules(target));
        return "accountant/journal";
    }

    /**
     * 弥生形式の仕訳 CSV を書き出す。
     *
     * <p>ダウンロードは読み取りなので GET でよい（PRG の対象は状態を変える操作）。
     */
    @GetMapping("/journal/export.csv")
    public ResponseEntity<byte[]> exportJournal(@RequestParam(required = false) String month) {
        YearMonth target = parseMonth(month);
        String csv = journalExport.exportYayoi(target);
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"" + journalExport.fileNameFor(target) + "\"")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    // ========================================================================
    //  ⑤ 対応表の設定
    // ========================================================================

    @GetMapping("/rules")
    public String rulesPage(@RequestParam(required = false) String month, Model model) {
        putCommon(model, month);
        model.addAttribute("rules",
                rules.findAllByOrderByCategoryAscTaxRatePercentDescDeductionRatePercentDesc());
        return "accountant/rules";
    }

    /**
     * 対応表の 1 行を直す。
     *
     * <p>ここは税理士が書き換えてよい数少ない場所です。
     * 店の記録ではなく<b>会計ソフトへの翻訳の仕方</b>だからで、
     * それを決めるのは税理士の仕事です。
     */
    @PostMapping("/rules/{id}")
    public String updateRule(@PathVariable Long id,
                             @RequestParam String accountName,
                             @RequestParam String taxClassName,
                             RedirectAttributes redirect) {
        if (accountName.isBlank() || taxClassName.isBlank()) {
            redirect.addFlashAttribute("flashErrors",
                    List.of("勘定科目と税区分は空にできません"));
            return "redirect:/accountant/rules";
        }
        rules.findById(id).ifPresent(rule -> {
            rule.setAccountName(accountName.trim());
            rule.setTaxClassName(taxClassName.trim());
            rules.save(rule);
        });
        redirect.addFlashAttribute("flashSuccess", "対応表を直しました");
        return "redirect:/accountant/rules";
    }

    // ========================================================================
    //  補助
    // ========================================================================

    /**
     * 画面の対象月を決める。指定が無ければ前月（{@code defaultMonth}）。
     *
     * <p>読めない値が来ても例外にせず既定の月に倒します。
     * URL を手で書き換えた程度で画面が落ちると、
     * 「壊した」と思わせてしまうためです。
     */
    private YearMonth parseMonth(String value) {
        if (value == null || value.isBlank()) {
            return accountantService.defaultMonth();
        }
        try {
            return YearMonth.parse(value);
        } catch (Exception e) {
            return accountantService.defaultMonth();
        }
    }
}
