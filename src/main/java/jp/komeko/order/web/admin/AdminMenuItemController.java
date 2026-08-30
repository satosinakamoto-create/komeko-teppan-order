package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Allergen;
import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.service.ImageStorageService;
import jp.komeko.order.service.MenuService;
import jp.komeko.order.web.admin.form.MenuItemForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商品（メニュー）の管理（{@code /admin/items}）。
 *
 * <p><b>登録・更新をコントローラで完結させている件について</b><br>
 * 本来は {@code MenuAdminService} のような Service クラスを作り、
 * 保存処理と {@code @Transactional} をそちらに置くのが定石です。
 * 業務ルールを画面の都合（HTTP・HTML）から切り離しておくと、
 * テストが書きやすく、あとから API を足すのも簡単になるからです。
 * ここでは<b>他の担当者と同時に作業していて既存 Service を編集できない</b>という
 * プロジェクト都合により、例外的にコントローラのメソッドで完結させています。
 *
 * <p><b>{@code open-in-view: false} と遅延読み込み</b><br>
 * このアプリは「画面を描いている間は DB 接続を持たない」設定です。
 * つまり<b>テンプレートの中で LAZY な関連をたどると例外になります</b>
 * （{@code LazyInitializationException}）。
 * そのため、画面で使う値は {@code @Transactional} なメソッドの中で
 * すべて読み終えてからモデルに詰めています。
 */
@Controller
@RequestMapping("/admin/items")
public class AdminMenuItemController {

    private static final Logger log = LoggerFactory.getLogger(AdminMenuItemController.class);

    private final MenuItemRepository menuItemRepository;
    private final CategoryRepository categoryRepository;
    private final MenuService menuService;
    private final ImageStorageService imageStorageService;

    public AdminMenuItemController(MenuItemRepository menuItemRepository,
                                   CategoryRepository categoryRepository,
                                   MenuService menuService,
                                   ImageStorageService imageStorageService) {
        this.menuItemRepository = menuItemRepository;
        this.categoryRepository = categoryRepository;
        this.menuService = menuService;
        this.imageStorageService = imageStorageService;
    }

    // ========================================================================
    //  一覧
    // ========================================================================

    /**
     * 商品一覧。カテゴリごとにまとめて表示する。
     *
     * <p>並び順は「カテゴリの並び順 → 商品の並び順 → id」です
     * （{@code MenuItemRepository#findAllForAdmin()} の JPQL で指定済み）。
     *
     * <p>Map のキーをカテゴリの id にしているのは、
     * エンティティをそのままキーにすると {@code equals} / {@code hashCode} の
     * 実装に依存してしまい、思わぬところで別物扱いされることがあるためです。
     */
    @GetMapping
    @Transactional(readOnly = true)
    public String list(Model model) {
        List<Category> categories = categoryRepository.findAllByOrderBySortOrderAscIdAsc();
        List<MenuItem> items = menuItemRepository.findAllForAdmin();

        // 先にカテゴリぶんの空リストを作っておくと、商品が 0 件のカテゴリも
        // 正しい並び順のまま画面に出せる
        Map<Long, List<MenuItem>> itemsByCategory = new LinkedHashMap<>();
        for (Category category : categories) {
            itemsByCategory.put(category.getId(), new ArrayList<>());
        }

        // オプションの数は画面に出したいが、optionGroups は LAZY。
        // 画面描画時には DB 接続が無いので、このトランザクションの中で数え切っておく。
        // （MenuItem.optionGroups には @BatchSize が付いているので、
        //   商品 30 件でも SQL は数回で済む）
        Map<Long, Integer> optionCounts = new LinkedHashMap<>();
        for (MenuItem item : items) {
            itemsByCategory
                    .computeIfAbsent(item.getCategory().getId(), key -> new ArrayList<>())
                    .add(item);
            optionCounts.put(item.getId(), item.getOptionGroups().size());
        }

        model.addAttribute("categories", categories);
        model.addAttribute("itemsByCategory", itemsByCategory);
        model.addAttribute("optionCounts", optionCounts);
        model.addAttribute("totalCount", items.size());
        return "admin/items";
    }

    // ========================================================================
    //  新規・編集フォームの表示
    // ========================================================================

    /** 新規登録フォーム。 */
    @GetMapping("/new")
    @Transactional(readOnly = true)
    public String createForm(Model model) {
        MenuItemForm form = new MenuItemForm();
        model.addAttribute("itemForm", form);
        prepareForm(model, form);
        return "admin/item-form";
    }

    /**
     * 編集フォーム。
     *
     * <p>商品が見つからないときは {@code MenuItemNotFoundException} を投げます。
     * この例外は {@code GlobalExceptionHandler} が受け止めて
     * 404 のページに変換してくれるので、ここで画面のことを気にする必要はありません。
     */
    @GetMapping("/{id}/edit")
    @Transactional(readOnly = true)
    public String editForm(@PathVariable("id") Long id, Model model) {
        MenuItem item = menuItemRepository.findById(id)
                .orElseThrow(() -> new MenuService.MenuItemNotFoundException(id));

        MenuItemForm form = MenuItemForm.of(item, item.getCategory().getId());
        model.addAttribute("itemForm", form);
        prepareForm(model, form);
        return "admin/item-form";
    }

    // ========================================================================
    //  登録
    // ========================================================================

    /**
     * 商品を登録する（画像アップロードあり）。
     *
     * <p>画像を保存するのは<b>入力チェックを全部通したあと</b>です。
     * 先に保存してしまうと、名前が未入力でやり直したときに
     * 使われないファイルだけがサーバに溜まっていきます。
     */
    @PostMapping
    @Transactional
    public String create(@Validated @ModelAttribute("itemForm") MenuItemForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        // 新規登録なので、id と「いまの画像」は必ず空にそろえる。
        // フォームクラスには setter があるため、リクエストに id=7 を紛れ込ませるだけで
        // 入力エラーで描き直したときの送信先が /admin/items/7（＝更新）に化けてしまい、
        // 次の送信で別の商品を上書きできてしまう。
        // 「表示用の項目は送られてきた値を信用しない」という update() と同じ方針。
        form.setId(null);
        form.setCurrentImagePath(null);

        Category category = resolveCategory(form, binding);
        if (binding.hasErrors()) {
            prepareForm(model, form);
            return "admin/item-form";
        }

        String storedImagePath = storeImage(form, binding);
        if (binding.hasErrors()) {
            prepareForm(model, form);
            return "admin/item-form";
        }

        MenuItem item = new MenuItem(category, form.getName().trim(), form.getPrice());
        applyForm(item, form);
        item.setImagePath(storedImagePath);
        menuItemRepository.save(item);

        log.info("商品を登録しました: {}", item.getName());
        redirectAttributes.addFlashAttribute("flashSuccess",
                "商品「%s」を登録しました".formatted(item.getName()));
        return "redirect:/admin/items";
    }

    // ========================================================================
    //  更新
    // ========================================================================

    /** 商品を更新する。 */
    @PostMapping("/{id}")
    @Transactional
    public String update(@PathVariable("id") Long id,
                         @Validated @ModelAttribute("itemForm") MenuItemForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        MenuItem item = menuItemRepository.findById(id)
                .orElseThrow(() -> new MenuService.MenuItemNotFoundException(id));

        // 画面を描き直すときに必要になる値を、送信値ではなく DB の値で埋め直す。
        // 「表示用の項目は送られてきた値を信用しない」のが安全側の作法。
        form.setId(id);
        form.setCurrentImagePath(item.getImagePath());

        Category category = resolveCategory(form, binding);
        if (binding.hasErrors()) {
            prepareForm(model, form);
            return "admin/item-form";
        }

        String storedImagePath = storeImage(form, binding);
        if (binding.hasErrors()) {
            prepareForm(model, form);
            return "admin/item-form";
        }

        String oldImagePath = item.getImagePath();
        item.setCategory(category);
        applyForm(item, form);

        if (storedImagePath != null) {
            // 新しい画像に差し替え。古いファイルは残しておくとゴミになるので消す
            item.setImagePath(storedImagePath);
            imageStorageService.delete(oldImagePath);
        } else if (form.isRemoveImage()) {
            item.setImagePath(null);
            imageStorageService.delete(oldImagePath);
        }
        // ※ 厳密には「DB のコミットが成功してからファイルを消す」べきです。
        //    ここで消したあとにロールバックすると、DB にはパスが残っているのに
        //    ファイルだけ無い状態になります。実務では
        //    TransactionSynchronizationManager で「コミット後に実行」を登録します。

        log.info("商品を更新しました: {}", item.getName());
        redirectAttributes.addFlashAttribute("flashSuccess",
                "商品「%s」を更新しました".formatted(item.getName()));
        return "redirect:/admin/items";
    }

    // ========================================================================
    //  削除・品切れ
    // ========================================================================

    /**
     * 商品を削除する。
     *
     * <p>過去の注文明細（{@code OrderLine}）は商品名と価格をコピーして持っているので、
     * 商品を消しても伝票は当時の内容のまま残ります。
     * オプションのグループと選択肢は {@code cascade = ALL} で一緒に消えます。
     */
    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        MenuItem item = menuItemRepository.findById(id)
                .orElseThrow(() -> new MenuService.MenuItemNotFoundException(id));

        String name = item.getName();
        String imagePath = item.getImagePath();
        menuItemRepository.delete(item);
        imageStorageService.delete(imagePath);

        log.info("商品を削除しました: {}", name);
        redirectAttributes.addFlashAttribute("flashSuccess", "商品「%s」を削除しました".formatted(name));
        return "redirect:/admin/items";
    }

    // 品切れを切り替える POST /{id}/soldout は 2026-08-27 に削除しました。
    //
    // 同じ soldOut を切り替える口が 3 つありました。
    //   商品一覧のボタン ／ 編集フォームのチェック ／ 品切れ・残数（KitchenController）
    // このメソッドが受けていた一覧のボタンだけは、他の 2 つでできることの
    // 部分集合で、ここにしかできないことがありませんでした。
    //
    // 価格 0 円（時価）の品では危険でもありました。
    // MenuItem#isOrderable は価格を見ないので、価格を入れないまま販売再開すると
    // ¥0 で注文できてしまいます。編集フォームなら価格の入力欄が同じ画面にあり、
    // 品切れ・残数は営業中に「今日は出せない」を伝えるための画面です。
    //
    // 切り替えのルール自体（MenuService#toggleSoldOut）は KitchenController が
    // 使い続けているので、そちらは消していません。

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /** フォーム画面で使う選択肢（カテゴリ・アレルゲン）と見出しをモデルへ詰める。 */
    private void prepareForm(Model model, MenuItemForm form) {
        model.addAttribute("pageTitle", form.isNew() ? "商品を追加" : "商品を編集");
        model.addAttribute("categories", categoryRepository.findAllByOrderBySortOrderAscIdAsc());

        // 表示義務のある特定原材料 8 品目を先に、推奨のものを後ろに分けて渡す。
        // 画面では罫線で区切って「必ず確認すべきもの」を目立たせる。
        List<Allergen> mandatory = new ArrayList<>();
        List<Allergen> optional = new ArrayList<>();
        for (Allergen allergen : Allergen.values()) {
            if (allergen.isMandatory()) {
                mandatory.add(allergen);
            } else {
                optional.add(allergen);
            }
        }
        model.addAttribute("mandatoryAllergens", mandatory);
        model.addAttribute("optionalAllergens", optional);
    }

    /**
     * フォームで選ばれたカテゴリを取得する。
     *
     * <p>存在しない id が送られてきたら（画面を改造された、削除直後など）
     * 例外にせず「入力エラー」として扱い、同じ画面に戻します。
     */
    private Category resolveCategory(MenuItemForm form, BindingResult binding) {
        if (form.getCategoryId() == null) {
            return null;   // 未選択は @NotNull がエラーにしてくれる
        }
        Category category = categoryRepository.findById(form.getCategoryId()).orElse(null);
        if (category == null) {
            binding.rejectValue("categoryId", "category.notFound", "選択されたカテゴリが見つかりません");
        }
        return category;
    }

    /**
     * 画像を保存し、公開パスを返す。ファイルが選ばれていなければ null。
     *
     * <p>{@code ImageStorageService} は拡張子や Content-Type が許可外だと
     * {@link IllegalArgumentException} を投げます。ここで受け止めて
     * 「画像の欄のエラー」に変換すると、他の入力欄と同じ見た目で表示できます。
     */
    private String storeImage(MenuItemForm form, BindingResult binding) {
        MultipartFile file = form.getImage();
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return imageStorageService.store(file);
        } catch (IllegalArgumentException e) {
            binding.rejectValue("image", "image.invalid", e.getMessage());
            return null;
        } catch (UncheckedIOException e) {
            log.warn("画像の保存に失敗しました", e);
            binding.rejectValue("image", "image.io", "画像の保存に失敗しました。もう一度お試しください");
            return null;
        }
    }

    /** フォームの内容をエンティティへ写す（画像とカテゴリを除く）。 */
    private void applyForm(MenuItem item, MenuItemForm form) {
        item.setName(form.getName().trim());
        item.setDescription(blankToNull(form.getDescription()));
        item.setPrice(form.getPrice());
        item.setCookMinutes(form.getCookMinutes());
        item.setSortOrder(form.getSortOrder());
        item.setSoldOut(form.isSoldOut());
        item.setVisible(form.isVisible());
        item.setRecommended(form.isRecommended());
        item.setAllergens(toEnumSet(form.getAllergens()));
    }

    /**
     * {@code Set<Allergen>} を {@link EnumSet} に変換する。
     *
     * <p><b>落とし穴：</b>{@code EnumSet.copyOf(collection)} は、
     * 渡されたコレクションが EnumSet ではなく<b>かつ空</b>のとき
     * 「どの enum 型か判断できない」という理由で {@link IllegalArgumentException} を投げます。
     * {@code MenuItem#setAllergens} が内部で {@code EnumSet.copyOf} を使っているため、
     * 「アレルゲンを 1 つもチェックしない」だけで落ちてしまいます。
     * そこでここで空かどうかを見て、空なら {@code EnumSet.noneOf} を渡します。
     */
    private EnumSet<Allergen> toEnumSet(Set<Allergen> allergens) {
        if (allergens == null || allergens.isEmpty()) {
            return EnumSet.noneOf(Allergen.class);
        }
        return EnumSet.copyOf(allergens);
    }

    /** 空文字・空白だけの入力は null にそろえる（DB に "" を残さないため）。 */
    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
