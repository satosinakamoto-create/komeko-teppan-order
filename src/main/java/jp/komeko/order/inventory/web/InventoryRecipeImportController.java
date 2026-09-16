package jp.komeko.order.inventory.web;

import jakarta.validation.Valid;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.inventory.domain.Ingredient;
import jp.komeko.order.inventory.service.IngredientService;
import jp.komeko.order.inventory.service.RecipeCsvParser;
import jp.komeko.order.inventory.service.RecipeService;
import jp.komeko.order.inventory.service.RecipeTextParser;
import jp.komeko.order.inventory.web.form.RecipeImportForm;
import jp.komeko.order.inventory.web.form.RecipeImportItemForm;
import jp.komeko.order.inventory.web.form.RecipeImportLineForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

/**
 * レシピの取り込み（設計 ト09b 806:8314・2026-09-16）。
 *
 * <p><b>エクセルやノートに書いたレシピを、この 1 画面で取り込みます。</b>
 * 流れは「読む → 人が照合して直す → まとめて登録」で、
 * これはレシート確認画面（{@link InventoryPurchaseController}）とまったく同じ形です。
 *
 * <p><b>AI が無くても成立します。</b>写真なしで開けば空の確認画面が出て、
 * 手で入力して登録できます。{@code ANTHROPIC_API_KEY} が無い環境でも
 * この画面は使えます。AI は後からこの入れ物に値を詰めるだけです。
 *
 * <p><b>JavaScript を使いません。</b>「＋材料を足す」「✕」「＋その場で作成」は
 * どれも <b>submit ボタンをコマンドとして使い</b>、フォーム全部を POST して
 * 描き直します。だから入力済みの他の行が一切消えません。
 * この画面は「記録の責任は人が持つ」場所なので、
 * 画面の状態とサーバの form が必ず一致していてほしいためです。
 *
 * <p><b>確認画面は POST でしか開きません。</b>まだ何も保存していないので、
 * PRG から意図的に外しています（レシートと同じ判断）。
 * 途中で再読み込みしても、中途半端なレシピが残ることはありません。
 *
 * <p>取り込み元のサンプル（前職のエクセル・ノート）が集まったら、
 * 同じ画面に AI 読取の入口を足します。画面と保存の仕組みは変わりません。
 */
@Controller
@RequestMapping("/inventory/recipes/import")
@ConditionalOnProperty(prefix = "app.inventory", name = "enabled", havingValue = "true")
public class InventoryRecipeImportController {

    private static final Logger log = LoggerFactory.getLogger(InventoryRecipeImportController.class);

    private final RecipeService recipeService;
    private final IngredientService ingredientService;
    private final RecipeTextParser textParser;
    private final RecipeCsvParser csvParser;

    public InventoryRecipeImportController(RecipeService recipeService,
                                           IngredientService ingredientService,
                                           RecipeTextParser textParser,
                                           RecipeCsvParser csvParser) {
        this.recipeService = recipeService;
        this.ingredientService = ingredientService;
        this.textParser = textParser;
        this.csvParser = csvParser;
    }

    /**
     * 選択肢はどの分岐でも要ります。
     *
     * <p>{@code @ModelAttribute} で全分岐に載せているのは、
     * 再描画の道が増えても詰め忘れが起きないようにするためです
     * （行を足す・行を消す・食材を作る・検証エラー…と分岐が多い）。
     */
    @ModelAttribute
    public void commonAttributes(Model model) {
        model.addAttribute("ingredients", recipeService.selectableIngredients());
        model.addAttribute("menuItems", recipeService.importableMenuItems());
        model.addAttribute("units", jp.komeko.order.inventory.domain.IngredientUnit.values());
    }

    // ========================================================================
    //  入口
    // ========================================================================

    /**
     * 取り込みの入口（設計 ト09d 842:10404）。写真・CSV・貼り付けの 3 択。
     *
     * <p><b>3 つのうち 2 つは AI が要りません。</b>CSV と貼り付けは自前の解析なので、
     * {@code ANTHROPIC_API_KEY} が無くても課金ゼロで動きます。写真だけが鍵を要ります。
     * 鍵が無いときは写真のボタンだけ無効にし、理由を画面に出します
     * （カードごと消すと「その道がある」ことすら伝わらない）。
     */
    @GetMapping
    public String start(Model model) {
        // AI 読取はまだ実装していないので常に false。
        // 鍵の有無ではなく「この機能がまだ無い」ことを表している。
        // 実装したら InventoryProperties の判定に差し替える。
        model.addAttribute("ocrAvailable", false);
        model.addAttribute("stage", "start");
        return "inventory/recipe-import";
    }

    /**
     * 貼り付けたテキストから取り込む（設計 ト09d 842:10569）。
     *
     * <p>スマホのメモや LINE に書いたレシピをそのまま貼るだけ。
     * <b>AI を使わないので鍵も課金も要りません。</b>
     */
    @PostMapping("/text")
    public String fromText(@RequestParam(required = false) String pasted, Model model) {
        List<RecipeTextParser.ParsedItem> parsed = textParser.parse(pasted);
        if (parsed.isEmpty()) {
            model.addAttribute("ocrAvailable", false);
            model.addAttribute("stage", "start");
            model.addAttribute("flashErrors",
                    List.of("読み取れる材料がありませんでした。1 行に「材料 分量」の形で書いてください"));
            return "inventory/recipe-import";
        }
        return confirmOf(parsed, null, "貼り付けたテキスト", model);
    }

    /**
     * CSV から取り込む（設計 ト09d 842:10558）。
     *
     * <p>前職のエクセルをそのまま。<b>AI を通さないので、いちばん正確です。</b>
     * 列が見つからないときは黙って 0 件にせず、理由を画面に出します——
     * 「取り込んだのに何も出てこない」がいちばん困るためです。
     */
    @PostMapping("/csv")
    public String fromCsv(@RequestParam(required = false) String csvText, Model model) {
        List<RecipeTextParser.ParsedItem> parsed;
        try {
            parsed = csvParser.parse(csvText);
        } catch (IllegalArgumentException e) {
            model.addAttribute("ocrAvailable", false);
            model.addAttribute("stage", "start");
            model.addAttribute("flashErrors", List.of(e.getMessage()));
            return "inventory/recipe-import";
        }
        if (parsed.isEmpty()) {
            model.addAttribute("ocrAvailable", false);
            model.addAttribute("stage", "start");
            model.addAttribute("flashErrors", List.of("読み取れる行がありませんでした"));
            return "inventory/recipe-import";
        }
        return confirmOf(parsed, null, "CSV", model);
    }

    /**
     * 読み取った結果を確認画面の形にする。
     *
     * <p><b>ここで自動照合まで済ませます。</b>商品名・材料名から
     * 登録済みのものを探して、見つかれば選んだ状態で出します。
     * 見つからなければ空のまま——画面が赤く出して人に選ばせます。
     * <b>勝手に近いものを選びません</b>（間違った商品にレシピが入るほうが害が大きい）。
     */
    private String confirmOf(List<RecipeTextParser.ParsedItem> parsed,
                             String imagePath, String sourceName, Model model) {
        RecipeImportForm form = new RecipeImportForm();
        form.setImagePath(imagePath);
        form.setSourceName(sourceName);
        form.setItems(new ArrayList<>());

        for (RecipeTextParser.ParsedItem item : parsed) {
            RecipeImportItemForm card = new RecipeImportItemForm();
            card.setReadName(item.name());
            MenuItem matched = recipeService.findByNameForImport(item.name());
            if (matched != null) {
                card.setMenuItemId(matched.getId());
            }
            List<RecipeImportLineForm> lines = new ArrayList<>();
            for (RecipeTextParser.ParsedLine read : item.lines()) {
                RecipeImportLineForm line = new RecipeImportLineForm();
                line.setReadName(read.name());
                line.setQtyPerItem(read.quantity());
                Ingredient found = ingredientService.findByNameForImport(read.name());
                if (found != null) {
                    line.setIngredientId(found.getId());
                }
                lines.add(line);
            }
            // 足したくなったとき用に空行を 1 本
            lines.add(new RecipeImportLineForm());
            card.setLines(lines);
            form.getItems().add(card);
        }

        model.addAttribute("recipeImportForm", form);
        model.addAttribute("stage", "confirm");
        return "inventory/recipe-import";
    }

    /**
     * 写真なしで確認画面を開く（手入力）。
     *
     * <p>AI が使えないときの逃げ道であり、<b>いまはこちらが本道</b>です
     * （取り込み元のサンプルが集まるまで AI 読取を作らないため）。
     */
    @PostMapping("/manual")
    public String manual(Model model) {
        model.addAttribute("recipeImportForm", RecipeImportForm.manual());
        model.addAttribute("stage", "confirm");
        return "inventory/recipe-import";
    }

    // ========================================================================
    //  確認画面での操作と登録
    // ========================================================================

    /**
     * 確認画面からの POST をぜんぶ受けます。
     *
     * <p><b>押されたボタンの名前で行き先が変わります。</b>
     * 保存に見えて保存ではない操作（行を足す等）が多いので、
     * 先にそれらを折り返してから登録の処理に入ります。
     *
     * @param addLine             「＋ 材料を足す」。値はカードの番号
     * @param addItem             「＋ 商品を足す」。値は未使用
     * @param removeRow           材料行の「✕」。値は {@code カード番号:行番号}
     * @param removeItem          「✕ この品を外す」。値はカードの番号
     * @param createIngredientRow 「＋ その場で作成」。値は {@code カード番号:行番号}
     * @param cancel              「やめる（何も登録しない）」
     * @param register            「◯ 品をまとめて登録」
     */
    @PostMapping
    public String submit(@Valid @ModelAttribute("recipeImportForm") RecipeImportForm form,
                         BindingResult bindingResult,
                         @RequestParam(required = false) Integer addLine,
                         @RequestParam(required = false) String addItem,
                         @RequestParam(required = false) String removeRow,
                         @RequestParam(required = false) Integer removeItem,
                         @RequestParam(required = false) String createIngredientRow,
                         @RequestParam(required = false) String cancel,
                         @RequestParam(required = false) String register,
                         Model model,
                         RedirectAttributes redirect) {

        // ── やめる ──
        // 何も保存していないので、そのまま原価表へ戻すだけ。
        if (cancel != null) {
            redirect.addFlashAttribute("flashInfo", "取り込みをやめました（何も登録していません）");
            return "redirect:/inventory/recipes";
        }

        // ── 保存ではなく「確認画面での操作」だったときは、ここで折り返す ──
        //
        // どれもフォーム全部を POST して描き直すので、入力済みの他の行は消えません。
        if (addLine != null) {
            itemAt(form, addLine).ifPresent(item -> item.getLines().add(new RecipeImportLineForm()));
            return redraw(model);
        }
        if (addItem != null) {
            form.getItems().add(RecipeImportItemForm.manual());
            return redraw(model);
        }
        if (removeItem != null) {
            if (removeItem >= 0 && removeItem < form.getItems().size()) {
                form.getItems().remove(removeItem.intValue());
            }
            // 全部消すと入力欄が 1 つも無い画面になるので、空のカードを 1 枚残す
            if (form.getItems().isEmpty()) {
                form.getItems().add(RecipeImportItemForm.manual());
            }
            return redraw(model);
        }
        if (removeRow != null) {
            removeLine(form, removeRow);
            return redraw(model);
        }
        if (createIngredientRow != null) {
            createIngredientFor(form, createIngredientRow, model);
            return redraw(model);
        }

        // ── ここから登録 ──
        if (register == null) {
            // ボタン名が無い POST（Enter キーなど）。保存はせず描き直す。
            return redraw(model);
        }

        List<String> errors = validateForRegister(form);
        if (!errors.isEmpty() || bindingResult.hasErrors()) {
            for (String e : errors) {
                bindingResult.reject("recipeImport", e);
            }
            return redraw(model);
        }

        int items = 0;
        int lines = 0;
        for (RecipeImportItemForm item : form.filledItems()) {
            if (!item.isReady()) {
                continue;
            }
            for (RecipeImportLineForm line : item.filledLines()) {
                recipeService.addLine(item.getMenuItemId(), line.getIngredientId(),
                        line.getQtyPerItem(), null);
                lines++;
            }
            items++;
        }
        log.info("レシピを取り込みました: {} 品 {} 行", items, lines);
        redirect.addFlashAttribute("flashSuccess",
                "%d 品・%d 材料を登録しました".formatted(items, lines));
        return "redirect:/inventory/recipes";
    }

    // ========================================================================
    //  部品
    // ========================================================================

    /** 確認画面を描き直す。stage を詰め忘れると入口の画面が出てしまうので 1 箇所にまとめる。 */
    private String redraw(Model model) {
        model.addAttribute("stage", "confirm");
        return "inventory/recipe-import";
    }

    private java.util.Optional<RecipeImportItemForm> itemAt(RecipeImportForm form, int index) {
        if (index < 0 || index >= form.getItems().size()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(form.getItems().get(index));
    }

    /**
     * 「カード番号:行番号」を読む。
     *
     * <p>入れ子の表なので、ボタンの value に 2 つの数字を載せています。
     * 読めない値（改ざん・古い画面）は黙って無視します——
     * エラーにしても人が直せるものが無いためです。
     */
    private int[] parsePosition(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void removeLine(RecipeImportForm form, String raw) {
        int[] at = parsePosition(raw);
        if (at == null) {
            return;
        }
        itemAt(form, at[0]).ifPresent(item -> {
            if (at[1] >= 0 && at[1] < item.getLines().size()) {
                item.getLines().remove(at[1]);
            }
            if (item.getLines().isEmpty()) {
                item.getLines().add(new RecipeImportLineForm());
            }
        });
    }

    /**
     * その行の材料名で食材を作り、その場で紐付ける。
     *
     * <p>レシート確認画面の「食材登録」と同じ仕組みです。
     * 別画面へ行かずに済むので、取り込みの途中で手が止まりません。
     *
     * <p><b>同じ名前が既にあれば作りません。</b>作ってしまうと
     * 「大葉」が 2 つできて在庫も原価も割れます。
     */
    private void createIngredientFor(RecipeImportForm form, String raw, Model model) {
        int[] at = parsePosition(raw);
        if (at == null) {
            return;
        }
        RecipeImportItemForm item = itemAt(form, at[0]).orElse(null);
        if (item == null || at[1] < 0 || at[1] >= item.getLines().size()) {
            return;
        }
        RecipeImportLineForm line = item.getLines().get(at[1]);
        String name = line.getReadName() == null ? "" : line.getReadName().trim();
        if (name.isEmpty()) {
            model.addAttribute("flashErrors",
                    List.of("材料名が空の行からは食材を作れません"));
            return;
        }

        Ingredient ingredient = ingredientService.activeIngredients().stream()
                .filter(i -> name.equals(i.getName()))
                .findFirst()
                .orElseGet(() -> ingredientService.create(
                        name, line.getNewUnit(), null, null, null));
        line.setIngredientId(ingredient.getId());

        model.addAttribute("flashSuccess",
                "食材「" + ingredient.getName() + "」をこの行に紐付けました");
        // 作った食材が選択肢に出るよう読み直す
        model.addAttribute("ingredients", recipeService.selectableIngredients());
    }

    /**
     * 登録できる状態かを見る。
     *
     * <p><b>分量が空の行を通しません。</b>通すとその材料は原価にも消費にも入らず、
     * 「登録したのに在庫が減らない」という形で静かに跳ね返ります。
     * 気づく手掛かりが無いので、ここで止めるのが唯一の防ぎ方です。
     */
    private List<String> validateForRegister(RecipeImportForm form) {
        List<String> errors = new ArrayList<>();
        List<RecipeImportItemForm> items = form.filledItems();
        if (items.isEmpty()) {
            errors.add("取り込むものがありません");
            return errors;
        }
        boolean anyReady = false;
        for (RecipeImportItemForm item : items) {
            String label = item.getReadName() == null || item.getReadName().isBlank()
                    ? "商品" : "「" + item.getReadName() + "」";
            if (item.getMenuItemId() == null) {
                errors.add(label + " がどの商品か決まっていません。登録済みの商品から選んでください");
                continue;
            }
            List<RecipeImportLineForm> lines = item.filledLines();
            if (lines.isEmpty()) {
                errors.add(label + " の材料が 1 行もありません");
                continue;
            }
            for (RecipeImportLineForm line : lines) {
                String lineLabel = line.getReadName() == null || line.getReadName().isBlank()
                        ? "材料" : "「" + line.getReadName() + "」";
                if (line.getIngredientId() == null) {
                    errors.add(label + " の " + lineLabel + " がどの食材か決まっていません");
                } else if (line.getQtyPerItem() == null
                        || line.getQtyPerItem().signum() <= 0) {
                    errors.add(label + " の " + lineLabel
                            + " の分量を入れてください（空だと原価にも在庫にも入りません）");
                }
            }
            anyReady = true;
        }
        if (!anyReady && errors.isEmpty()) {
            errors.add("登録できる品がありません");
        }
        return errors;
    }
}
