package jp.komeko.order.web.admin;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.OptionChoice;
import jp.komeko.order.domain.OptionGroup;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.repository.OptionChoiceRepository;
import jp.komeko.order.repository.OptionGroupRepository;
import jp.komeko.order.service.MenuService;
import jp.komeko.order.web.admin.form.OptionChoiceForm;
import jp.komeko.order.web.admin.form.OptionGroupForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品オプション（トッピング・サイズなど）の編集画面。
 *
 * <p>URL は商品にぶら下がるもの（{@code /admin/items/{id}/options}）と、
 * オプションそのものを操作するもの（{@code /admin/options/...}）に分かれるため、
 * クラスには {@code @RequestMapping} を付けず、メソッドごとにフルパスを書いています。
 *
 * <p><b>親子関係の消し方（orphanRemoval）</b><br>
 * {@code MenuItem → OptionGroup → OptionChoice} は
 * 「親が消えたら子も消える」「親のリストから外したら DB からも消える」
 * という完全従属の関係（{@code cascade = ALL} + {@code orphanRemoval = true}）です。
 * そのため削除は<b>リポジトリの delete を直接呼ぶより、親のリストから外すほうが安全</b>です。
 * 親のリストに残ったまま子だけ消すと、JPA が混乱して例外になることがあります。
 *
 * <p><b>{@code @Transactional} をコントローラに付けている件</b><br>
 * 本来は Service 層に置くべき処理です。他の担当者と同時に作業していて
 * 既存 Service を編集できないため、ここでは例外的にコントローラで完結させています。
 */
@Controller
public class AdminOptionController {

    private static final Logger log = LoggerFactory.getLogger(AdminOptionController.class);

    private final MenuService menuService;
    private final MenuItemRepository menuItemRepository;
    private final OptionGroupRepository optionGroupRepository;
    private final OptionChoiceRepository optionChoiceRepository;

    public AdminOptionController(MenuService menuService,
                                 MenuItemRepository menuItemRepository,
                                 OptionGroupRepository optionGroupRepository,
                                 OptionChoiceRepository optionChoiceRepository) {
        this.menuService = menuService;
        this.menuItemRepository = menuItemRepository;
        this.optionGroupRepository = optionGroupRepository;
        this.optionChoiceRepository = optionChoiceRepository;
    }

    // ========================================================================
    //  表示
    // ========================================================================

    /**
     * オプション編集画面。
     *
     * <p>{@link MenuService#itemWithOptions(Long)} はグループと選択肢まで
     * 読み終えた状態の商品を返してくれます。
     * このアプリは {@code open-in-view: false} なので、
     * 画面を描く時点では DB 接続がありません。
     * 「必要なものは読み終えてから渡す」というこの作りにとても助けられています。
     */
    @GetMapping("/admin/items/{id}/options")
    public String options(@PathVariable("id") Long id, Model model) {
        prepareOptions(id, model);
        return "admin/options";
    }

    // ========================================================================
    //  グループの追加・削除
    // ========================================================================

    /**
     * オプショングループを追加する。
     *
     * <p>「最大選択数 &lt; 最小選択数」という組み合わせは、項目単体を見ても分かりません。
     * こうした<b>複数項目にまたがるチェック</b>は
     * {@link BindingResult#rejectValue} で自分で足します。
     * 第 2 引数はエラーコード（メッセージファイルを使うときのキー）、
     * 第 3 引数がそのまま画面に出る既定のメッセージです。
     */
    @PostMapping("/admin/items/{id}/options")
    @Transactional
    public String addGroup(@PathVariable("id") Long id,
                           @Validated @ModelAttribute("groupForm") OptionGroupForm form,
                           BindingResult binding,
                           Model model,
                           RedirectAttributes redirectAttributes) {
        if (form.getMinSelect() != null && form.getMaxSelect() != null
                && form.getMaxSelect() < form.getMinSelect()) {
            binding.rejectValue("maxSelect", "range.invalid",
                    "最大選択数は最小選択数以上にしてください");
        }

        if (binding.hasErrors()) {
            prepareOptions(id, model);
            return "admin/options";
        }

        MenuItem item = menuItemRepository.findById(id)
                .orElseThrow(() -> new MenuService.MenuItemNotFoundException(id));

        OptionGroup group = new OptionGroup(
                form.getName().trim(), form.getMinSelect(), form.getMaxSelect(), form.getSortOrder());
        // 双方向関連の両側をそろえるヘルパー。cascade = ALL なので
        // このリストに足すだけで、トランザクション終了時に INSERT される
        item.addOptionGroup(group);

        log.info("オプショングループを追加しました: {} / {}", item.getName(), group.getName());
        redirectAttributes.addFlashAttribute("flashSuccess",
                "オプション「%s」を追加しました".formatted(group.getName()));
        return "redirect:/admin/items/" + id + "/options";
    }

    /** オプショングループを削除する（中の選択肢も一緒に消える）。 */
    @PostMapping("/admin/options/{groupId}/delete")
    @Transactional
    public String deleteGroup(@PathVariable("groupId") Long groupId,
                              RedirectAttributes redirectAttributes) {
        OptionGroup group = optionGroupRepository.findByIdWithChoices(groupId).orElse(null);
        if (group == null) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("オプションが見つかりませんでした（すでに削除された可能性があります）"));
            return "redirect:/admin/items";
        }

        MenuItem item = group.getMenuItem();
        Long itemId = item.getId();
        String name = group.getName();

        // 親のリストから外す → orphanRemoval が効いて DELETE される
        item.removeOptionGroup(group);

        log.info("オプショングループを削除しました: {}", name);
        redirectAttributes.addFlashAttribute("flashSuccess", "オプション「%s」を削除しました".formatted(name));
        return "redirect:/admin/items/" + itemId + "/options";
    }

    // ========================================================================
    //  選択肢の追加・削除
    // ========================================================================

    /**
     * 選択肢を追加する。
     *
     * <p>1 つの画面にグループの数だけ「選択肢を追加」フォームが並びます。
     * {@code BindingResult} は 1 リクエストにつき 1 つぶんしか持てないので、
     * 「どのグループでエラーが起きたか（{@code choiceErrorGroupId}）」と
     * 「項目名 → メッセージの対応表（{@code choiceErrors}）」をモデルに入れ、
     * テンプレート側でそのグループのフォームだけエラー表示します。
     */
    @PostMapping("/admin/options/{groupId}/choices")
    @Transactional
    public String addChoice(@PathVariable("groupId") Long groupId,
                            @Validated @ModelAttribute("choiceForm") OptionChoiceForm form,
                            BindingResult binding,
                            Model model,
                            RedirectAttributes redirectAttributes) {
        OptionGroup group = optionGroupRepository.findByIdWithChoices(groupId).orElse(null);
        if (group == null) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("オプションが見つかりませんでした（すでに削除された可能性があります）"));
            return "redirect:/admin/items";
        }
        Long itemId = group.getMenuItem().getId();

        if (binding.hasErrors()) {
            prepareOptions(itemId, model);
            model.addAttribute("choiceErrorGroupId", groupId);
            model.addAttribute("choiceErrors", fieldErrors(binding));
            return "admin/options";
        }

        OptionChoice choice = new OptionChoice(
                form.getName().trim(), form.getExtraPrice(), form.getSortOrder());
        choice.setSoldOut(form.isSoldOut());
        choice.setDefaultSelected(form.isDefaultSelected());
        group.addChoice(choice);

        log.info("選択肢を追加しました: {} / {}", group.getName(), choice.getName());
        redirectAttributes.addFlashAttribute("flashSuccess",
                "選択肢「%s」を追加しました".formatted(choice.getName()));
        return "redirect:/admin/items/" + itemId + "/options";
    }

    /** 選択肢を 1 つ削除する。 */
    @PostMapping("/admin/choices/{choiceId}/delete")
    @Transactional
    public String deleteChoice(@PathVariable("choiceId") Long choiceId,
                               RedirectAttributes redirectAttributes) {
        OptionChoice choice = optionChoiceRepository.findById(choiceId).orElse(null);
        if (choice == null) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("選択肢が見つかりませんでした（すでに削除された可能性があります）"));
            return "redirect:/admin/items";
        }

        OptionGroup group = choice.getOptionGroup();
        Long itemId = group.getMenuItem().getId();
        String name = choice.getName();

        // グループの choices から外すと orphanRemoval で DELETE される
        group.getChoices().remove(choice);

        log.info("選択肢を削除しました: {}", name);
        redirectAttributes.addFlashAttribute("flashSuccess", "選択肢「%s」を削除しました".formatted(name));
        return "redirect:/admin/items/" + itemId + "/options";
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /**
     * オプション編集画面に必要な値をモデルへ詰める。
     *
     * <p>フォームのオブジェクトは「まだ入っていなければ」入れます。
     * 入力エラーで戻ってきたときは、{@code @ModelAttribute} が付いた引数によって
     * 送信内容入りのフォームがすでにモデルへ入っているので、
     * 上書きしてしまうと入力し直しになってしまうためです。
     */
    private void prepareOptions(Long itemId, Model model) {
        MenuItem item = menuService.itemWithOptions(itemId);

        model.addAttribute("activeNav", "admin");
        model.addAttribute("item", item);
        if (!model.containsAttribute("groupForm")) {
            model.addAttribute("groupForm", new OptionGroupForm());
        }
        if (!model.containsAttribute("choiceForm")) {
            model.addAttribute("choiceForm", new OptionChoiceForm());
        }
    }

    /**
     * 検証エラーを「項目名 → メッセージ」の Map にする。
     * 同じ項目に複数のエラーが付いたときは最初の 1 件だけ残します。
     */
    private Map<String, String> fieldErrors(BindingResult binding) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : binding.getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return errors;
    }
}
