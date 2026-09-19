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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 食材マスタと在庫の画面（{@code /inventory/ingredients}）。
 *
 * <p><b>この画面が答えるのは 1 つだけです。「いま何がどれだけあるか」。</b>
 * 残量と、少なくなっているものが一目で分かること。それ以上は望みません。
 *
 * <p><b>「あと◯営業日」はレシピ（Step 3）から出ます。</b>
 * レシピ未登録のメニューぶんは消費に入らないため、日数は実際より長めに出ます。
 * その旨の警告を画面に常時出しています。
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
    private final jp.komeko.order.service.ShopSettingService shopSettingService;

    /** 商品カテゴリ（お品書きの分類）。食材の分類ではない。 */
    private final jp.komeko.order.repository.CategoryRepository categoryRepository;

    public InventoryIngredientController(IngredientService ingredientService,
                                         StockService stockService,
                                         PurchaseService purchaseService,
                                         RecipeService recipeService,
                                         jp.komeko.order.service.ShopSettingService shopSettingService,
                                         jp.komeko.order.repository.CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
        this.ingredientService = ingredientService;
        this.stockService = stockService;
        this.purchaseService = purchaseService;
        this.recipeService = recipeService;
        this.shopSettingService = shopSettingService;
    }

    /**
     * 棚卸し・廃棄フォームの既定日。<b>暦日ではなく営業日</b>。
     *
     * <p>この店は深夜 1 時過ぎまで営業します。閉店後 0:30 に棚卸しをすると、
     * 暦の上ではもう「翌日」ですが、営業日はまだ前日です
     * （切り替えは既存設定の 5 時。売上・注文はすべて営業日で動いている）。
     * 暦日を既定にすると、消費の集計が「棚卸し日より後の営業日」を数える際に
     * <b>翌営業日の消費が丸ごと 1 日ぶん漏れ</b>、在庫が過大に出ます。
     */
    private java.time.LocalDate businessToday() {
        return shopSettingService.currentBusinessDate();
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
    /**
     * 食材・在庫の一覧（設計 ト04 725:4457）。
     *
     * <p><b>「カテゴリーから検索」は商品カテゴリです</b>（2026-09-19。
     * 店主の言葉「商品カテゴリのこと言ってる」）。お品書きの分類を選ぶと、
     * <b>その分類の商品に使われている食材</b>だけが残ります。
     *
     * <p><b>食材そのものの分類ではありません。</b>あれは 2026-09-16 に消した機能で
     * （{@code IngredientCategoryRemovedTest}・CLAUDE.md「やらないと決めたこと」）、
     * {@code Ingredient} に分類の項目はもうありません。
     * ここはレシピを辿るだけなので、<b>新しく入力してもらうものはありません</b>。
     *
     * <p>裏を返すと、<b>レシピが登録されていない商品の食材は出てきません</b>。
     * レシピが増えるほど、この絞り込みの網も広がります。
     *
     * <p><b>{@code category} は文字列で受け取ること。</b>
     * ブックマークや履歴に古い {@code ?category=VEGETABLE}（消した分類の名前）が
     * 残っている人がいます。{@code Long} で受けると数字でない値が
     * <b>400 で突き返され</b>、一覧そのものが開けません。
     * 知らない値は黙って無視して一覧を出すのが正しい振る舞いです
     * （{@code IngredientCategoryRemovedTest} が見張っています）。
     *
     * @param q        食材名の一部。入っていれば名前で絞り込む（2026-09-07 に追加）
     * @param category 商品カテゴリの ID。数字でなければ無視する
     */
    @GetMapping
    public String index(@RequestParam(required = false) String q,
                        @RequestParam(required = false) String category,
                        Model model) {
        Long categoryId = null;
        if (category != null && !category.isBlank()) {
            try {
                categoryId = Long.valueOf(category.trim());
            } catch (NumberFormatException ignored) {
                // 古い分類の名前（VEGETABLE など）。絞り込まずに全件を出す
                categoryId = null;
            }
        }
        List<StockLevel> all = stockService.currentLevels();
        List<StockLevel> levels = all;

        String keyword = (q == null) ? "" : q.trim();
        if (!keyword.isEmpty()) {
            String needle = keyword.toLowerCase();
            levels = levels.stream()
                    .filter(l -> l.ingredient().getName() != null
                            && l.ingredient().getName().toLowerCase().contains(needle))
                    .toList();
        }

        // ── カテゴリーから検索（商品カテゴリ）──
        //
        // ★ 探すときはカテゴリを無視する。商品・品切れ・残数と同じ考え方。
        //   「キャベツ」と打った人は、それがどの商品に使われているかを覚えていない。
        java.util.Map<Long, java.util.Set<Long>> byCategory =
                recipeService.ingredientIdsByMenuCategory();
        Long selectedCategoryId = keyword.isEmpty() ? categoryId : null;
        if (selectedCategoryId != null) {
            java.util.Set<Long> allowed =
                    byCategory.getOrDefault(selectedCategoryId, java.util.Set.of());
            levels = levels.stream()
                    .filter(l -> allowed.contains(l.ingredient().getId()))
                    .toList();
        }

        // 件数はレシピから数えた「その分類の商品が使う食材の数」。
        // 押す前に何品あるか読めるようにする（商品の一覧と同じ理由）
        List<CategoryPick> categoryPicks = categoryRepository
                .findAllByOrderBySortOrderAscIdAsc().stream()
                .map(c -> new CategoryPick(c.getId(), c.getName(),
                        byCategory.getOrDefault(c.getId(), java.util.Set.of()).size()))
                .toList();
        String selectedCategoryName = selectedCategoryId == null ? null
                : categoryPicks.stream()
                        .filter(p -> selectedCategoryId.equals(p.id()))
                        .map(CategoryPick::name)
                        .findFirst()
                        .orElse(null);

        model.addAttribute("categoryPicks", categoryPicks);
        model.addAttribute("selectedCategoryId", selectedCategoryId);
        model.addAttribute("selectedCategoryName", selectedCategoryName);

        int attention = 0;
        for (StockLevel level : levels) {
            if (level.needsAttention()) {
                attention++;
            }
        }

        model.addAttribute("q", keyword);
        model.addAttribute("levels", levels);
        model.addAttribute("attentionCount", attention);
        model.addAttribute("today", purchaseService.today());

        // レシピ未登録は「あと◯営業日」を静かに甘くする。件数を必ず出す。
        model.addAttribute("missingRecipeCount", recipeService.menuItemsWithoutRecipe().size());

        // 「教えれば在庫が正しくなる」宿題。埋もれさせないよう常に出す。
        model.addAttribute("unlearned", ingredientService.unlearnedAliases());
        model.addAttribute("linesNeedingQuantity", ingredientService.linesNeedingQuantity());

        if (!model.containsAttribute("stocktakeForm")) {
            model.addAttribute("stocktakeForm", new StocktakeForm(businessToday()));
        }
        return "inventory/ingredients";
    }

    /**
     * 棚卸し・廃棄を記録する（設計 現05 443:2940 / 2026-09-07）。
     *
     * <p>もとは一覧のいちばん下に置いていた 2 つのフォームを、専用ページに分けました。
     * 一覧は「いま何がどれだけあるか」を読む画面で、記録は仕込みの前後に
     * まとめてやる別の仕事です。
     *
     * @param ingredient 一覧の「記録する」から来たとき、その食材を選んだ状態で開く
     */
    @GetMapping("/record")
    public String recordForm(@RequestParam(required = false) Long ingredient, Model model) {
        model.addAttribute("levels", stockService.currentLevels());
        model.addAttribute("today", purchaseService.today());
        if (!model.containsAttribute("stocktakeForm")) {
            StocktakeForm form = new StocktakeForm(businessToday());
            form.setIngredientId(ingredient);
            model.addAttribute("stocktakeForm", form);
        }
        return "inventory/ingredient-record";
    }

    /**
     * 1 つの食材の詳細と、記録の履歴。
     *
     * <p><b>{@code id} は数字だけに絞っています（{@code \d+}）。</b>
     * 絞らないと、この配置が {@code /inventory/ingredients/なんとか} を
     * <b>全部拾ってしまいます</b>。文字列は {@code Long} に変換できないので
     * 400（不正なリクエスト）になり、見つからないことを伝えるはずの場面で
     * 「あなたの送り方が悪い」という画面が出ます。
     *
     * <p>2026-09-16 に分類をやめたとき、消したはずの
     * {@code /inventory/ingredients/categorize} が 404 ではなく 400 になって
     * 見つかりました（{@code IngredientCategoryRemovedTest}）。
     * 数字に絞れば、知らない道は素直に 404 になります。
     *
     * <p>存在しない id（数字だが該当なし）は 404 にせず一覧へ返します。
     * こちらは「あった食材が使用停止・削除された」場合で、
     * 行き止まりより一覧に戻すほうが親切だからです。
     */
    @GetMapping("/{id:\\d+}")
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
            StocktakeForm form = new StocktakeForm(businessToday());
            form.setIngredientId(id);
            model.addAttribute("stocktakeForm", form);
        }
        return "inventory/ingredient-detail";
    }

    // ========================================================================
    //  食材マスタの編集
    // ========================================================================

    /**
     * 新規登録のフォーム。
     *
     * <p>{@code returnTo} は「来た道」（2026-09-14、店主の指摘から）。
     * レシピ編集の途中で足りない食材に気づいてここへ来た人は、
     * 保存したらレシピ編集に戻りたい。もとは食材の詳細へ飛ばしていたので、
     * レシピ → 食材・在庫 → 登録 → 一覧 → レシピ・原価表 → 該当商品、
     * と往復 5 画面になっていた。
     */
    @GetMapping("/new")
    public String newIngredient(@RequestParam(required = false) String returnTo, Model model) {
        if (!model.containsAttribute("ingredientForm")) {
            model.addAttribute("ingredientForm", new IngredientForm());
        }
        String safe = safeReturnTo(returnTo);
        model.addAttribute("returnTo", safe);
        // どのレシピから来たのかを画面で名指しする（設計 ト04b・2026-09-14）。
        // 名前の取り出しは Service 側（LAZY 対策）。null なら案内そのものを出さない
        model.addAttribute("returnToName", recipeService.menuItemNameOf(recipeIdIn(safe)));
        return "inventory/ingredient-form";
    }

    /**
     * 検査済みの戻り先から、レシピ（商品）の id を取り出す。
     *
     * <p>引数は必ず {@link #safeReturnTo(String)} を通した値を渡すこと。
     * 生のリクエスト値をここへ入れると、形の検査を飛ばして
     * 任意の id を引かせる道ができてしまいます。
     */
    private Long recipeIdIn(String safeReturnTo) {
        if (safeReturnTo == null) {
            return null;
        }
        return Long.valueOf(safeReturnTo.substring("/inventory/recipes/".length()));
    }

    /**
     * 戻り先として信じてよい形だけを通す。
     *
     * <p>リダイレクト先をリクエストから受け取る作りは、放っておくと
     * {@code returnTo=https://偽サイト} のようなオープンリダイレクトの
     * 入り口になる。いま戻り道が要るのはレシピ編集だけなので、
     * {@code /inventory/recipes/数字} の形しか許さない。増やすときも
     * 「先頭一致」ではなく形そのものを足すこと。
     */
    private String safeReturnTo(String returnTo) {
        if (returnTo != null && returnTo.matches("/inventory/recipes/\\d+")) {
            return returnTo;
        }
        return null;
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("ingredientForm") IngredientForm form,
                         BindingResult bindingResult,
                         @RequestParam(required = false) String returnTo,
                         Model model,
                         RedirectAttributes redirect) {
        if (form.getName() != null && ingredientService.nameTaken(form.getName().trim(), null)) {
            bindingResult.rejectValue("name", "duplicate", "同じ名前の食材がすでにあります");
        }
        if (bindingResult.hasErrors()) {
            // 入力エラーでも来た道は失くさない（直して保存すればちゃんと戻れる）
            model.addAttribute("returnTo", safeReturnTo(returnTo));
            return "inventory/ingredient-form";
        }
        Ingredient saved = ingredientService.create(form.getName().trim(), form.getUnit(),
                form.getLowThresholdQty(), form.getCostOverride(), form.getMemo());
        String back = safeReturnTo(returnTo);
        if (back != null) {
            // レシピ編集から来た人は、そのレシピへ返す。
            // 新しい食材はまだ単価 0 円なので、原価が正しくなるのはレシートを
            // 登録するか単価を手で入れてから（レシピ画面の注意書きがその案内をする）
            redirect.addFlashAttribute("flashSuccess",
                    "食材「" + saved.getName() + "」を登録しました。材料の列から選べます");
            return "redirect:" + back;
        }
        redirect.addFlashAttribute("flashSuccess",
                "食材「" + saved.getName() + "」を登録しました。棚卸しをすると在庫の計算が始まります");
        return "redirect:/inventory/ingredients/" + saved.getId();
    }

    // GET 側（detail）と同じ理由で数字に絞る。こちらを絞り忘れると、
    // 知らない道が 404 ではなく 405（このパスに GET は許されていない）になる。
    // 「パス自体は存在する」と言っていることになり、消したはずの URL が生きて見える。
    @PostMapping("/{id:\\d+}")
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
        // 止める前ではなく止めた後に知らせる（止めること自体は正しい操作なので妨げない）。
        // 黙って止めると、この食材を使う全メニューの原価が「単価不明」になり、
        // 気づく手掛かりが原価表の警告だけになる。
        List<String> using = ingredientService.menuItemsUsing(id);
        ingredientService.deactivate(id);
        if (using.isEmpty()) {
            redirect.addFlashAttribute("flashInfo",
                    "使用停止にしました。過去の仕入れと原価の記録はそのまま残ります");
        } else {
            redirect.addFlashAttribute("flashInfo",
                    "使用停止にしました。ただし " + using.size() + " 品のレシピ（"
                            + String.join("、", using.subList(0, Math.min(3, using.size())))
                            + (using.size() > 3 ? " ほか" : "")
                            + "）がこの食材を使っています。該当メニューの原価は単価不明になります");
        }
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
                            @RequestParam(required = false) String origin,
                            @AuthenticationPrincipal StaffUserDetails user,
                            RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            redirect.addFlashAttribute("flashErrors", errorMessages(bindingResult));
            return backTo(origin, form);
        }
        stockService.recordStocktake(form.getIngredientId(), form.getTakenOn(),
                form.getQuantity(), form.getMemo(), user != null ? user.getId() : null);
        redirect.addFlashAttribute("flashSuccess",
                "棚卸しを記録しました。ここからの入出庫で在庫を計算します");
        return backTo(origin, form);
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
                         @RequestParam(required = false) String origin,
                         @AuthenticationPrincipal StaffUserDetails user,
                         RedirectAttributes redirect) {
        if (bindingResult.hasErrors()) {
            redirect.addFlashAttribute("flashErrors", errorMessages(bindingResult));
            return backTo(origin, form);
        }
        BigDecimal delta = form.getQuantity().abs().negate();
        stockService.recordAdjustment(form.getIngredientId(), form.getTakenOn(), delta,
                form.getReason(), form.getMemo(), user != null ? user.getId() : null);
        redirect.addFlashAttribute("flashSuccess",
                form.getReason().getLabel() + "として記録しました");
        return backTo(origin, form);
    }

    /**
     * 記録したあと、来た画面へ返す。
     *
     * <p>詳細画面から記録したのに一覧へ飛ばされると、見ていた食材を見失います
     * （2026-08-31 のUI監査の指摘）。行き先は自分で組み立てた 2 つの URL だけで、
     * 画面から渡された文字列をそのままリダイレクト先にはしません
     * （外部サイトへ飛ばされる穴になるため）。
     */
    private String backTo(String origin, StocktakeForm form) {
        if ("detail".equals(origin) && form.getIngredientId() != null) {
            return "redirect:/inventory/ingredients/" + form.getIngredientId();
        }
        // 記録ページから来たら記録ページへ戻す（2026-09-07）。
        // 仕込み後は何品も続けて記録するので、1 件ごとに一覧へ飛ばされると
        // 毎回開き直すことになる。選んでいた食材も選び直させない
        if ("record".equals(origin)) {
            return form.getIngredientId() != null
                    ? "redirect:/inventory/ingredients/record?ingredient=" + form.getIngredientId()
                    : "redirect:/inventory/ingredients/record";
        }
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
        // ★ 0 以下を覚えさせない。
        //   0 を入れると「学習済みなのに在庫に積めない」という宙ぶらりんの状態になり、
        //   未学習の一覧（qty_per_unit IS NULL）からも消えるので、直す入口が無くなる。
        //   画面側の min=0.001 はブラウザ次第で外せるため、ここでも必ず弾く。
        if (qtyPerUnit == null || qtyPerUnit.signum() <= 0) {
            redirect.addFlashAttribute("flashErrors",
                    List.of("1個あたりの量は 0 より大きい値で入力してください"));
            return "redirect:/inventory/ingredients";
        }
        // ★ 桁あふれもここで弾く。@RequestParam には @Digits が効かない
        //   （列は precision=12, scale=3。あふれると 500 になる）
        if (QuantityDigits.overflows(qtyPerUnit)) {
            redirect.addFlashAttribute("flashErrors",
                    List.of("1個あたりの量が大きすぎます。" + QuantityDigits.LIMIT_NOTE));
            return "redirect:/inventory/ingredients";
        }
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

    /**
     * 「カテゴリーから検索」の 1 行（2026-09-19、設計 ト04 725:4457）。
     *
     * <p><b>商品カテゴリです。</b>お品書きの分類で、食材そのものの分類ではありません。
     *
     * @param id    商品カテゴリの ID。押すと {@code ?category=id} で絞り込む
     * @param name  画面に出す名前
     * @param count その分類の商品が使っている食材の数（レシピから数える）
     */
    public record CategoryPick(Long id, String name, int count) {
    }
}
