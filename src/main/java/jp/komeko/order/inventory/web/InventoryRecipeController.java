package jp.komeko.order.inventory.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.inventory.service.RecipeCost;
import jp.komeko.order.inventory.service.RecipeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;

/**
 * レシピと原価表の画面（{@code /inventory/recipes}）。
 *
 * <p><b>これが前職のエクセルの原価表そのものです。</b>
 * 商品名・材料・分量・原価・原価率が 1 枚に並びます。
 * 違うのは、食材を仕入れ直すたびに原価が勝手に更新されることだけ。
 *
 * <p>レシピを登録すると、その商品が売れるたびに材料が在庫から引かれ、
 * 「あと◯営業日で切れる」が食材・在庫の画面に出るようになります。
 * <b>登録した商品の分から順に効きます。</b>全部そろえる必要はありません。
 */
@Controller
@RequestMapping("/inventory/recipes")
@ConditionalOnProperty(prefix = "app.inventory", name = "enabled", havingValue = "true")
public class InventoryRecipeController {

    private final RecipeService recipeService;

    public InventoryRecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    /**
     * 原価表の一覧。
     *
     * <p>レシピ未登録の商品も行として出します。一覧から消してしまうと、
     * 登録し忘れていることに気づけません。
     */
    @GetMapping
    public String index(Model model) {
        List<RecipeCost> costs = recipeService.costTable();

        int registered = 0;
        for (RecipeCost cost : costs) {
            if (!cost.isEmpty()) {
                registered++;
            }
        }

        model.addAttribute("costs", costs);
        model.addAttribute("registeredCount", registered);
        model.addAttribute("missing", recipeService.menuItemsWithoutRecipe());
        return "inventory/recipes";
    }

    /** 1 商品のレシピを編集する。 */
    @GetMapping("/{menuItemId}")
    public String edit(@PathVariable Long menuItemId, Model model) {
        MenuItem item = recipeService.findMenuItem(menuItemId);
        if (item == null) {
            return "redirect:/inventory/recipes";
        }
        model.addAttribute("menuItem", item);
        model.addAttribute("cost", recipeService.costOf(menuItemId));
        model.addAttribute("ingredients", recipeService.selectableIngredients());
        if (!model.containsAttribute("recipeLineForm")) {
            model.addAttribute("recipeLineForm", new RecipeLineForm());
        }
        return "inventory/recipe-edit";
    }

    /** 材料を 1 行足す。 */
    @PostMapping("/{menuItemId}/lines")
    public String addLine(@PathVariable Long menuItemId,
                          @Valid @ModelAttribute("recipeLineForm") RecipeLineForm form,
                          BindingResult bindingResult,
                          Model model,
                          RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            return edit(menuItemId, model);
        }
        recipeService.addLine(menuItemId, form.getIngredientId(), form.getQtyPerItem(), form.getMemo());
        redirect.addFlashAttribute("flashSuccess", "材料を追加しました");
        return "redirect:/inventory/recipes/" + menuItemId;
    }

    /** 分量を直す。 */
    @PostMapping("/{menuItemId}/lines/{lineId}")
    public String updateLine(@PathVariable Long menuItemId,
                             @PathVariable Long lineId,
                             @RequestParam BigDecimal qtyPerItem,
                             @RequestParam(required = false) String memo,
                             RedirectAttributes redirect) {
        recipeService.updateLine(lineId, qtyPerItem, memo);
        redirect.addFlashAttribute("flashSuccess", "分量を直しました");
        return "redirect:/inventory/recipes/" + menuItemId;
    }

    /** 材料を 1 行外す。 */
    @PostMapping("/{menuItemId}/lines/{lineId}/delete")
    public String removeLine(@PathVariable Long menuItemId,
                             @PathVariable Long lineId,
                             RedirectAttributes redirect) {
        recipeService.removeLine(lineId);
        redirect.addFlashAttribute("flashInfo", "材料を外しました");
        return "redirect:/inventory/recipes/" + menuItemId;
    }

    /** 材料 1 行ぶんの入力欄。 */
    public static class RecipeLineForm {

        @NotNull(message = "食材を選んでください")
        private Long ingredientId;

        @NotNull(message = "1品あたりの量を入力してください")
        @DecimalMin(value = "0.001", message = "1品あたりの量は0より大きい値を入れてください")
        private BigDecimal qtyPerItem;

        @Size(max = 100, message = "メモは100文字以内で入力してください")
        private String memo;

        public Long getIngredientId() {
            return ingredientId;
        }

        public void setIngredientId(Long ingredientId) {
            this.ingredientId = ingredientId;
        }

        public BigDecimal getQtyPerItem() {
            return qtyPerItem;
        }

        public void setQtyPerItem(BigDecimal qtyPerItem) {
            this.qtyPerItem = qtyPerItem;
        }

        public String getMemo() {
            return memo;
        }

        public void setMemo(String memo) {
            this.memo = memo;
        }
    }
}
