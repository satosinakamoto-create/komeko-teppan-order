package jp.komeko.order.inventory.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jp.komeko.order.inventory.domain.TaxRatePeriod;
import jp.komeko.order.inventory.service.PurchaseService;
import jp.komeko.order.inventory.service.TaxRuleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

/**
 * 税率・控除率マスタの管理（{@code /inventory/tax-rates}）。
 *
 * <p><b>なぜこの画面が要るのか</b><br>
 * 設計では「税制改正はコードを直さず<b>行を足すだけ</b>で済む」ことにしてあります。
 * 実際そうなるようデータ駆動で作り、控除率は 2031 年の終了分まで投入済みです。
 * ところが<b>その行を足す画面を用意し忘れていました</b>（2026-08-30 に発見）。
 * このままだと、飲食料品 1% の法案が成立したときに DB を直接触るしかありません。
 *
 * <p>使うのは数年に 1 回です。それでも、<b>使う日が来たときに無いと詰みます</b>。
 *
 * <p><b>消す操作を作らない</b><br>
 * この画面には削除がありません。過去の行は、その行を根拠に計算された
 * 仕入れの記録が既に存在するからです。消すと去年の帳簿の答えが変わります。
 * 改定は「前の行に終了日を入れて、新しい行を足す」だけ。
 * 履歴が積み上がっていく作りにしてあります。
 *
 * <p><b>ADMIN 限定</b><br>
 * 誤操作の被害が大きいので、店長だけが開けます。
 * {@code SecurityConfig} は {@code /inventory/**} を STAFF 以上にしているので、
 * ここはメソッドレベルで一段絞ります（共有ファイルを増やさずに済む）。
 */
@Controller
@RequestMapping("/inventory/tax-rates")
@ConditionalOnProperty(prefix = "app.inventory", name = "enabled", havingValue = "true")
@PreAuthorize("hasRole('ADMIN')")
public class InventoryTaxRateController {

    private final TaxRuleService taxRuleService;
    private final PurchaseService purchaseService;

    public InventoryTaxRateController(TaxRuleService taxRuleService, PurchaseService purchaseService) {
        this.taxRuleService = taxRuleService;
        this.purchaseService = purchaseService;
    }

    @ModelAttribute
    public void commonAttributes(Model model) {
        model.addAttribute("rateClasses", List.of(
                TaxRatePeriod.CLASS_STANDARD,
                TaxRatePeriod.CLASS_REDUCED_FOOD,
                TaxRatePeriod.CLASS_REDUCED_NEWSPAPER));
    }

    /** 一覧。いま何%が有効で、いつ切り替わるのかを一望できるようにする。 */
    @GetMapping
    public String index(Model model) {
        LocalDate today = purchaseService.today();
        model.addAttribute("today", today);
        model.addAttribute("taxRates", taxRuleService.allTaxRates());
        model.addAttribute("deductionRates", taxRuleService.allDeductionRates());
        model.addAttribute("warnings", taxRuleService.masterWarnings(today));
        if (!model.containsAttribute("taxRateForm")) {
            model.addAttribute("taxRateForm", new TaxRateForm());
        }
        if (!model.containsAttribute("deductionRateForm")) {
            model.addAttribute("deductionRateForm", new DeductionRateForm());
        }
        return "inventory/tax-rates";
    }

    /** 税率の改定を登録する。前の行は自動で閉じられる。 */
    @PostMapping("/tax")
    public String addTaxRate(@Valid @ModelAttribute("taxRateForm") TaxRateForm form,
                             BindingResult bindingResult,
                             Model model,
                             RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            return index(model);
        }
        taxRuleService.addTaxRate(form.getRateClass(), form.getRatePercent(),
                form.getValidFrom(), form.getNote());
        redirect.addFlashAttribute("flashSuccess",
                form.getRateClass() + " を " + form.getValidFrom() + " から "
                        + form.getRatePercent() + "% にしました");
        return "redirect:/inventory/tax-rates";
    }

    /** 経過措置の控除率の改定を登録する。 */
    @PostMapping("/deduction")
    public String addDeductionRate(@Valid @ModelAttribute("deductionRateForm") DeductionRateForm form,
                                   BindingResult bindingResult,
                                   Model model,
                                   RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            return index(model);
        }
        taxRuleService.addDeductionRate(form.getRatePercent(), form.getValidFrom(), form.getNote());
        redirect.addFlashAttribute("flashSuccess",
                form.getValidFrom() + " から控除率を " + form.getRatePercent() + "% にしました");
        return "redirect:/inventory/tax-rates";
    }

    // ========================================================================
    //  フォーム
    // ========================================================================

    /** 税率の改定を登録するフォーム。 */
    public static class TaxRateForm {

        @NotBlank(message = "区分を選んでください")
        private String rateClass = TaxRatePeriod.CLASS_REDUCED_FOOD;

        @NotNull(message = "税率を入力してください")
        @Min(value = 0, message = "税率は0以上で入力してください")
        @Max(value = 100, message = "税率は100以下で入力してください")
        private Integer ratePercent;

        @NotNull(message = "施行日を入力してください")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate validFrom;

        @Size(max = 200, message = "メモは200文字以内で入力してください")
        private String note;

        public String getRateClass() {
            return rateClass;
        }

        public void setRateClass(String rateClass) {
            this.rateClass = rateClass;
        }

        public Integer getRatePercent() {
            return ratePercent;
        }

        public void setRatePercent(Integer ratePercent) {
            this.ratePercent = ratePercent;
        }

        public LocalDate getValidFrom() {
            return validFrom;
        }

        public void setValidFrom(LocalDate validFrom) {
            this.validFrom = validFrom;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    /** 控除率の改定を登録するフォーム。 */
    public static class DeductionRateForm {

        @NotNull(message = "控除率を入力してください")
        @Min(value = 0, message = "控除率は0以上で入力してください")
        @Max(value = 100, message = "控除率は100以下で入力してください")
        private Integer ratePercent;

        @NotNull(message = "施行日を入力してください")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate validFrom;

        @Size(max = 200, message = "メモは200文字以内で入力してください")
        private String note;

        public Integer getRatePercent() {
            return ratePercent;
        }

        public void setRatePercent(Integer ratePercent) {
            this.ratePercent = ratePercent;
        }

        public LocalDate getValidFrom() {
            return validFrom;
        }

        public void setValidFrom(LocalDate validFrom) {
            this.validFrom = validFrom;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
