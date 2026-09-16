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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        // カテゴリ名は見出しの補足に出す。画面側で item.category を辿ると
        // 描画時に DB 接続が無く落ちるので、ここで文字列にして渡す
        model.addAttribute("categoryName", recipeService.categoryNameOf(menuItemId));
        model.addAttribute("cost", recipeService.costOf(menuItemId));
        model.addAttribute("ingredients", recipeService.selectableIngredients());
        // その他材料費はフォームの初期値に使うだけ。合計への反映は cost 側が持っている
        model.addAttribute("otherCost", recipeService.otherCostOf(menuItemId));
        // 単価が分からない食材を名指しするため（設計 ト09c 819:8916）。
        // 「1 種類あります」だけだと、どれを直せばいいか画面から分からない。
        model.addAttribute("unknownCostNames", unknownCostNamesOf(model));
        if (!model.containsAttribute("recipeLineForm")) {
            model.addAttribute("recipeLineForm", new RecipeLineForm());
        }
        return "inventory/recipe-edit";
    }

    /**
     * 材料の分量をまとめて保存する（設計 ト09c・2026-09-16）。
     *
     * <p><b>行ごとのボタンをやめ、表に 1 つにまとめました。</b>
     * Figma の行には「直す」ボタンが無く、入力と ✕ だけです。自動保存に見えますが、
     * このプロジェクトは JavaScript を使わない方針なので自動保存にはできません。
     * 「行ごとに 1 ボタン」と「表に 1 ボタン」なら、後者のほうが Figma に近く、
     * <b>何度直しても押すのは 1 回</b>で済みます。
     * 2026-09-14 の差分チェックで「相談」に上げていた件の決着です。
     *
     * <p>送られてくるのは {@code qty_<行id>} という名前の欄です。
     * 知らない id・読めない値は<b>黙って飛ばします</b>——古い画面から送られた、
     * あるいは他の人が先に消した行で、ここで止めても人に直せるものがありません。
     */
    @PostMapping("/{menuItemId}/quantities")
    public String updateQuantities(@PathVariable Long menuItemId,
                                   @RequestParam Map<String, String> params,
                                   RedirectAttributes redirect) {
        int updated = 0;
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!entry.getKey().startsWith("qty_")) {
                continue;
            }
            Long lineId;
            try {
                lineId = Long.valueOf(entry.getKey().substring("qty_".length()));
            } catch (NumberFormatException ignored) {
                continue;   // 古い画面・改ざん。直せるものが無いので飛ばす
            }
            BigDecimal qty;
            try {
                qty = new BigDecimal(entry.getValue().trim());
            } catch (RuntimeException ignored) {
                errors.add("分量に数字でないものが入っています");
                continue;
            }
            if (qty.signum() <= 0) {
                errors.add("分量は 0 より大きい数で入れてください");
                continue;
            }
            if (QuantityDigits.overflows(qty)) {
                errors.add("1品あたりの量が大きすぎます。" + QuantityDigits.LIMIT_NOTE);
                continue;
            }
            recipeService.updateLine(lineId, qty, null);
            updated++;
        }

        if (!errors.isEmpty()) {
            redirect.addFlashAttribute("flashErrors", errors);
        } else {
            redirect.addFlashAttribute("flashSuccess", updated + " 行の分量を保存しました");
        }
        return "redirect:/inventory/recipes/" + menuItemId;
    }

    /**
     * 単価が分からない食材の名前。
     *
     * <p>モデルに載せた {@code cost} から拾います。Service に増やさないのは、
     * これが<b>画面の言い回しのための情報</b>で、原価の計算には関係ないためです。
     */
    private List<String> unknownCostNamesOf(Model model) {
        Object attr = model.getAttribute("cost");
        if (!(attr instanceof RecipeCost cost)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (RecipeCost.LineCost lc : cost.lines()) {
            if (lc.isUnknown() && lc.line().getIngredient() != null) {
                names.add(lc.line().getIngredient().getName());
            }
        }
        return names;
    }

    /**
     * その他材料費を入れる・直す・外す（2026-09-16）。
     *
     * <p>ソース・青のり・かつお節のように 1 品あたり何グラムかを測らない材料を、
     * まとめて金額で置くための口です。<b>食材を 1 つも登録せず、ここだけ入れても
     * 原価が出ます</b>（店主の「食材入れずに原価だけ入れて保存できないの？」）。
     *
     * <p>空欄と 0 は「外す」と同じ扱いにします。0 円の行を残すと
     * 「検討した結果 0 円」と「まだ入れていない」が画面から区別できません。
     *
     * <p>マイナスはサービス層が {@link IllegalArgumentException} で弾きます。
     * ここで捕まえて画面のメッセージに変えます——投げっぱなしにすると
     * エラー画面になり、入力していた他の値も消えるためです。
     */
    @PostMapping("/{menuItemId}/other-cost")
    public String setOtherCost(@PathVariable Long menuItemId,
                               @RequestParam(required = false) Integer amountIncludingTax,
                               @RequestParam(required = false) String memo,
                               RedirectAttributes redirect) {
        int amount = amountIncludingTax == null ? 0 : amountIncludingTax;
        try {
            recipeService.setOtherCost(menuItemId, amount, trimToNull(memo));
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("flashErrors", java.util.List.of(e.getMessage()));
            return "redirect:/inventory/recipes/" + menuItemId;
        }
        redirect.addFlashAttribute("flashSuccess",
                amount == 0 ? "その他材料費を外しました" : "その他材料費を設定しました");
        return "redirect:/inventory/recipes/" + menuItemId;
    }

    /** 空白だけのメモは null にする。「 」を保存しても読む人の役に立たない。 */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
        // ★ 0 以下はサーバでも弾く。画面の min=0.001 はブラウザ次第で外せる。
        //   負の分量が通ると「売れるほど在庫が増える」ことになり、
        //   0 だと消費されない材料としてレシピに居座る。
        //   新規追加側（RecipeLineForm の @DecimalMin）と基準を揃える。
        if (qtyPerItem == null || qtyPerItem.signum() <= 0) {
            redirect.addFlashAttribute("flashErrors",
                    java.util.List.of("1品あたりの量は0より大きい値で入力してください"));
            return "redirect:/inventory/recipes/" + menuItemId;
        }
        // ★ 桁あふれもここで弾く。@RequestParam には @Digits が効かない
        //   （新規追加側は RecipeLineForm の @Digits が受け持つ）
        if (QuantityDigits.overflows(qtyPerItem)) {
            redirect.addFlashAttribute("flashErrors",
                    java.util.List.of("1品あたりの量が大きすぎます。" + QuantityDigits.LIMIT_NOTE));
            return "redirect:/inventory/recipes/" + menuItemId;
        }
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
        // 列は precision=12, scale=3（RecipeLine.qtyPerItem）
        @jakarta.validation.constraints.Digits(integer = 9, fraction = 3,
                message = "1品あたりの量が大きすぎます。整数は 9 桁まで・小数は 3 桁までで入力してください")
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
