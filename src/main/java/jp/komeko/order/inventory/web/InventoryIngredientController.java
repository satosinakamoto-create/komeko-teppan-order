package jp.komeko.order.inventory.web;

import jakarta.validation.Valid;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.domain.IngredientUnit;
import jp.komeko.order.inventory.domain.ItemAlias;
import jp.komeko.order.inventory.domain.StocktakeReason;
import jp.komeko.order.inventory.service.IngredientService;
import jp.komeko.order.inventory.service.PurchaseService;
import jp.komeko.order.inventory.service.RecipeService;
import jp.komeko.order.inventory.service.StockLevel;
import jp.komeko.order.inventory.service.StockService;
import jp.komeko.order.inventory.web.form.IngredientForm;
import jp.komeko.order.inventory.web.form.StocktakeForm;
import jp.komeko.order.security.StaffUserDetails;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;

/**
 * 食材マスタと在庫の画面（{@code /inventory/ingredients}）。
 *
 * <p><b>この画面が答えるのは 1 つだけです。「いま何がどれだけあるか」。</b>
 * 残量と、少なくなっているものが一目で分かること。それ以上は望みません。
 *
 * <p><b>「あと◯営業日」はここにはまだ出ません。</b>
 * それを出すには、注文からどれだけ食材が減ったかを知る必要があり、
 * つまりレシピの登録が要ります。レシピの層（Step 3）の仕事です。
 * いまは<b>残量と、設定した警告残量を下回ったかどうか</b>までを扱います。
 *
 * <p>棚卸しの入力もこの画面に置いています。残量を見て「合ってないな」と
 * 思った、まさにその場で直せるほうが、別画面に移るより実際に使われるからです。
 */
@Controller
@RequestMapping("/inventory/ingredients")
@ConditionalOnProperty(prefix = "app.inventory", name = "enabled", havingValue = "true")
public class InventoryIngredientController {

    private final IngredientService ingredientService;
    private final StockService stockService;
    private final PurchaseService purchaseService;
    private final RecipeService recipeService;

    public InventoryIngredientController(IngredientService ingredientService,
                                         StockService stockService,
                                         PurchaseService purchaseService,
                                         RecipeService recipeService) {
        this.ingredientService = ingredientService;
        this.stockService = stockService;
        this.purchaseService = purchaseService;
        this.recipeService = recipeService;
    }

    @ModelAttribute
    public void commonAttributes(Model model) {
        model.addAttribute("units", IngredientUnit.values());
        model.addAttribute("reasons", StocktakeReason.values());
    }

    // ========================================================================
    //  在庫一覧
    // ========================================================================

    /**
     * 現在庫の一覧。
     *
     * <p>並び順は食材マスタの設定どおりで、少ない順には並べ替えません。
     * <b>いつも同じ場所に同じ食材がある</b>ほうが、毎日見る人には速いからです。
     * 注意が要るものはバッジで目立たせ、上部に件数を出します。
     */
    @GetMapping
    public String index(Model model) {
        List<StockLevel> levels = stockService.currentLevels();

        int attention = 0;
        for (StockLevel level : levels) {
            if (level.needsAttention()) {
                attention++;
            }
        }

        model.addAttribute("levels", levels);
        model.addAttribute("attentionCount", attention);
        model.addAttribute("today", purchaseService.today());

        // レシピ未登録は「あと◯営業日」を静かに甘くする。件数を必ず出す。
        model.addAttribute("missingRecipeCount", recipeService.menuItemsWithoutRecipe().size());

        // 「教えれば在庫が正しくなる」宿題。埋もれさせないよう常に出す。
        model.addAttribute("unlearned", ingredientService.unlearnedAliases());
        model.addAttribute("linesNeedingQuantity", ingredientService.linesNeedingQuantity());

        if (!model.containsAttribute("stocktakeForm")) {
            model.addAttribute("stocktakeForm", new StocktakeForm(purchaseService.today()));
        }
        return "inventory/ingredients";
    }

    /** 1 つの食材の詳細と、記録の履歴。 */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Ingredient ingredient = ingredientService.find(id);
        if (ingredient == null) {
            return "redirect:/inventory/ingredients";
        }
        model.addAttribute("ingredient", ingredient);
        model.addAttribute("level", stockService.levelOf(id));
        model.addAttribute("history", stockService.historyOf(id));
        if (!model.containsAttribute("ingredientForm")) {
            model.addAttribute("ingredientForm", IngredientForm.of(ingredient));
        }
        if (!model.containsAttribute("stocktakeForm")) {
            StocktakeForm form = new StocktakeForm(purchaseService.today());
            form.setIngredientId(id);
            model.addAttribute("stocktakeForm", form);
        }
        return "inventory/ingredient-detail";
    }

    // ========================================================================
    //  食材マスタの編集
    // ========================================================================

    /** 新規登録のフォーム。 */
    @GetMapping("/new")
    public String newIngredient(Model model) {
        if (!model.containsAttribute("ingredientForm")) {
            model.addAttribute("ingredientForm", new IngredientForm());
        }
        return "inventory/ingredient-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("ingredientForm") IngredientForm form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirect) {
        if (form.getName() != null && ingredientService.nameTaken(form.getName().trim(), null)) {
            bindingResult.rejectValue("name", "duplicate", "同じ名前の食材がすでにあります");
        }
        if (bindingResult.hasErrors()) {
            return "inventory/ingredient-form";
        }
        Ingredient saved = ingredientService.create(form.getName().trim(), form.getUnit(),
                form.getLowThresholdQty(), form.getCostOverride(), form.getMemo());
        redirect.addFlashAttribute("flashSuccess",
                "食材「" + saved.getName() + "」を登録しました。棚卸しをすると在庫の計算が始まります");
        return "redirect:/inventory/ingredients/" + saved.getId();
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("ingredientForm") IngredientForm form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirect) {
        if (form.getName() != null && ingredientService.nameTaken(form.getName().trim(), id)) {
            bindingResult.rejectValue("name", "duplicate", "同じ名前の食材がすでにあります");
        }
        if (bindingResult.hasErrors()) {
            return detail(id, model);
        }
        ingredientService.update(id, form.getName().trim(), form.getUnit(),
                form.getLowThresholdQty(), form.getCostOverride(),
                form.getSortOrder(), form.isActive(), form.getMemo());
        redirect.addFlashAttribute("flashSuccess", "食材を更新しました");
        return "redirect:/inventory/ingredients/" + id;
    }

    /** 使用停止にする。行は消さない。 */
    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id, RedirectAttributes redirect) {
        ingredientService.deactivate(id);
        redirect.addFlashAttribute("flashInfo",
                "使用停止にしました。過去の仕入れと原価の記録はそのまま残ります");
        return "redirect:/inventory/ingredients";
    }

    // ========================================================================
    //  棚卸し・廃棄
    // ========================================================================

    /**
     * 棚卸し（実測リセット）を記録する。
     *
     * <p>この記録が以後の在庫計算の起点になります。
     * それまでの理論値と実測のずれ（打ち粉・まかない・目分量のブレ）は、
     * ここで実測側に吸収されます。
     */
    @PostMapping("/stocktake")
    public String stocktake(@Valid @ModelAttribute("stocktakeForm") StocktakeForm form,
                            BindingResult bindingResult,
                            @AuthenticationPrincipal StaffUserDetails user,
                            RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            redirect.addFlashAttribute("flashErrors", errorMessages(bindingResult));
            return "redirect:/inventory/ingredients";
        }
        stockService.recordStocktake(form.getIngredientId(), form.getTakenOn(),
                form.getQuantity(), form.getMemo(), user != null ? user.getId() : null);
        redirect.addFlashAttribute("flashSuccess",
                "棚卸しを記録しました。ここからの入出庫で在庫を計算します");
        return "redirect:/inventory/ingredients";
    }

    /**
     * 廃棄・まかないなどの減少を記録する。
     *
     * <p><b>画面では減らす量を正の数で入力してもらい、ここで符号を付けます。</b>
     * マイナスを人に入力させると、付け忘れて逆に増えるという事故が必ず起きます。
     */
    @PostMapping("/adjust")
    public String adjust(@Valid @ModelAttribute("stocktakeForm") StocktakeForm form,
                         BindingResult bindingResult,
                         @AuthenticationPrincipal StaffUserDetails user,
                         RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            redirect.addFlashAttribute("flashErrors", errorMessages(bindingResult));
            return "redirect:/inventory/ingredients";
        }
        BigDecimal delta = form.getQuantity().abs().negate();
        stockService.recordAdjustment(form.getIngredientId(), form.getTakenOn(), delta,
                form.getReason(), form.getMemo(), user != null ? user.getId() : null);
        redirect.addFlashAttribute("flashSuccess",
                form.getReason().getLabel() + "として記録しました");
        return "redirect:/inventory/ingredients";
    }

    // ========================================================================
    //  入り数の記憶
    // ========================================================================

    /**
     * 「このレシートの品名は、この食材の◯◯ぶん」を教える。
     *
     * <p>ここで教えた内容は<b>次回以降のレシートに自動で効きます</b>。
     * すでに保存した仕入れ明細はさかのぼって直しません。
     * 過去の記録は、そのとき人が確認したままにしておくのが筋だからです。
     */
    @PostMapping("/aliases/{id}/learn")
    public String learnAlias(@PathVariable Long id,
                             @RequestParam BigDecimal qtyPerUnit,
                             RedirectAttributes redirect) {
        ItemAlias alias = ingredientService.relearnQuantity(id, qtyPerUnit);
        if (alias == null) {
            redirect.addFlashAttribute("flashErrors", List.of("その紐付けは見つかりませんでした"));
        } else {
            redirect.addFlashAttribute("flashSuccess",
                    "「" + alias.getAliasText() + "」を覚えました。次のレシートから自動で在庫に入ります");
        }
        return "redirect:/inventory/ingredients";
    }

    /** 覚えさせた紐付けを取り消す。 */
    @PostMapping("/aliases/{id}/forget")
    public String forgetAlias(@PathVariable Long id, RedirectAttributes redirect) {
        ingredientService.forget(id);
        redirect.addFlashAttribute("flashInfo", "紐付けを忘れました");
        return "redirect:/inventory/ingredients";
    }

    private List<String> errorMessages(BindingResult bindingResult) {
        return bindingResult.getAllErrors().stream()
                .map(e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : "入力を確認してください")
                .toList();
    }
}
