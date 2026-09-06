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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
     * 商品一覧のタブ。
     *
     * <p>「掲載」と「販売」は性質の違う軸です。
     * 掲載はメニューに載せるかどうか（長い話）、販売は今夜出せるかどうか（その日の話）。
     * 本来は 2 つの絞り込みですが、店主が探したいのは
     * 「いま何かおかしい商品」なので、1 列のタブにまとめてあります。
     *
     * @param key   URL に出る値（?tab=）
     * @param label 画面に出す名前
     * @param 条件   その商品がこのタブに入るか
     */
    public record ItemTab(String key, String label, java.util.function.Predicate<MenuItem> 条件) {
    }

    /** タブの並び。左から「広い→狭い」。すべてが最初に来るのは、既定がそこだから。 */
    private static final List<ItemTab> TABS = List.of(
            new ItemTab("all", "すべて", item -> true),
            new ItemTab("published", "掲載中", MenuItem::isVisible),
            new ItemTab("soldout", "品切れ", item -> item.isSoldOut() || item.isOutOfStock()),
            new ItemTab("hidden", "掲載停止", item -> !item.isVisible()));

    /** 画面に渡すタブ 1 つぶん。 */
    public record TabView(String key, String label, int count, boolean active) {
    }

    /**
     * 商品一覧。状態でタブを分けた 1 枚の表で出す。
     *
     * <p>並び順は「カテゴリの並び順 → 商品の並び順 → id」です
     * （{@code MenuItemRepository#findAllForAdmin()} の JPQL で指定済み）。
     *
     * <p><b>件数は絞り込む前の全件から数えます。</b>
     * 絞ったあとの母集合で数えると、品切れタブを開いている間は
     * 品切れ以外の件数が 0 になり、タブが「行き先の看板」として働きません。
     *
     * <p><b>{@code q}（商品を探す）を受け取っていませんでした（2026-09-05 に修正）。</b><br>
     * 画面には検索の入力欄があり、{@code GET /admin/items?q=...&tab=...} を送っていたのに、
     * このメソッドが {@code q} を受け取っていなかったため、
     * <b>何を入れても全件がそのまま返っていました</b>。
     * 検索結果が 0 件でも「見つからない」でもなく、94 品がそのまま並ぶので、
     * 探せていないことに気づけません。エラーも出ません。
     *
     * <p>絞り込みは名前の部分一致（大文字小文字を無視）です。
     * カテゴリ名で引けるようにもできますが、店主がこの欄に打つのは
     * 「豚ロース」のような品名なので、まず名前だけにしています。
     *
     * @param q 商品名の一部。空・未指定なら絞り込まない
     */
    @GetMapping
    @Transactional(readOnly = true)
    public String list(@RequestParam(required = false, defaultValue = "all") String tab,
                       @RequestParam(required = false) String q,
                       Model model) {
        List<Category> categories = categoryRepository.findAllByOrderBySortOrderAscIdAsc();
        List<MenuItem> all = menuItemRepository.findAllForAdmin();

        // 検索語。前後の空白だけの入力は「指定なし」と同じに扱う。
        // 変数を分けているのは、下のラムダから参照する items を再代入しないため
        // （ラムダが掴めるのは final か、実質的に final な変数だけ）。
        String keyword = (q == null) ? "" : q.trim();
        String needle = keyword.toLowerCase();
        List<MenuItem> items = keyword.isEmpty()
                ? all
                : all.stream()
                        .filter(item -> item.getName() != null
                                && item.getName().toLowerCase().contains(needle))
                        .toList();

        ItemTab selected = TABS.stream()
                .filter(t -> t.key().equals(tab))
                .findFirst()
                .orElse(TABS.get(0));

        // ★ 件数は all（絞り込む前）から数えること。items（絞ったあと）ではない。
        //
        //   検索を足したとき、いったん items から数えるようにしてしまった。
        //   「タブは検索結果を分けたものだから」という理屈だったが、実際に出る絵は
        //     「たこ」で 5 件 → すべて 5 ／ 掲載中 5 ／ 品切れ 0 ／ 掲載停止 0
        //   になり、タブが「そこに何件あるか」の看板として働かなくなる。
        //   タブの役目は行き先を示すことなので、母集合は常に全件で固定する。
        List<TabView> tabs = TABS.stream()
                .map(t -> new TabView(t.key(), t.label(),
                        (int) all.stream().filter(t.条件()).count(),
                        t.key().equals(selected.key())))
                .toList();

        // オプションの数は画面に出したいが、optionGroups は LAZY。
        // 画面描画時には DB 接続が無いので、このトランザクションの中で数え切っておく。
        // （MenuItem.optionGroups には @BatchSize が付いているので、
        //   商品 30 件でも SQL は数回で済む）
        Map<Long, Integer> optionCounts = new LinkedHashMap<>();
        for (MenuItem item : items) {
            optionCounts.put(item.getId(), item.getOptionGroups().size());
        }

        // カテゴリ名は画面で使うが Category は LAZY 参照になり得るので、
        // ここで id → 名前 に写し取っておく（描画時には DB 接続が無い）
        Map<Long, String> categoryNames = new LinkedHashMap<>();
        for (Category category : categories) {
            categoryNames.put(category.getId(), category.getName());
        }

        model.addAttribute("categories", categories);
        model.addAttribute("rows", items.stream().filter(selected.条件()).toList());
        model.addAttribute("categoryNames", categoryNames);
        model.addAttribute("optionCounts", optionCounts);
        model.addAttribute("tabs", tabs);
        model.addAttribute("currentTab", selected.key());
        // 見出しの「掲載中 94 品」も、タブの件数と同じく店の全体像を出す
        model.addAttribute("totalCount", all.size());
        model.addAttribute("visibleCount", (int) all.stream().filter(MenuItem::isVisible).count());
        // 入力した語を画面に返す。返さないと、検索したあとに入力欄が空に戻り、
        // 何で絞った結果を見ているのか分からなくなる
        model.addAttribute("q", keyword);
        // 並び替えのボタンを出してよいか。
        //
        // 絞り込んでいる最中は出しません。画面に見えている隣の行が、
        // 本当の隣とは限らないからです。「上へ」を押すと隠れている品と
        // 入れ替わり、画面上は何も起きていないように見えます。
        // 並べ替えは全体が見えているときの作業なので、そのときだけ出します。
        model.addAttribute("canReorder", keyword.isEmpty() && "all".equals(selected.key()));
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
        // 並び順は新規フォームで聞いていないので、そのカテゴリの末尾に付ける。
        // 既定の 0 のままだと、追加した品が看板メニューの上に割り込みます。
        // 店主が気づいて直すまで、お客さまにはその並びで見えています。
        item.setSortOrder(menuService.nextItemSortOrder(category.getId()));
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

    /**
     * 商品をひとつ上（または下）へ動かす。
     *
     * <p>動くのは<b>同じカテゴリの中だけ</b>です。上下でカテゴリをまたげてしまうと、
     * 並べ替えのつもりで商品の所属が変わります。
     * カテゴリを移したいときは編集フォームから変更してください。
     *
     * <p>ボタンは絞り込みのかかっていない一覧にしか出していません
     * （{@code canReorder}）。見えている隣の行が本当の隣とは限らない状態で
     * 押せると、隠れた品と入れ替わって「押しても動かない」ように見えるためです。
     * だから戻り先にタブや検索語を持ち帰る必要もありません。
     */
    @PostMapping("/{id}/move")
    public String move(@PathVariable("id") Long id,
                       @RequestParam boolean up,
                       RedirectAttributes redirectAttributes) {
        // 端まで来ていたら何も起きない。無反応に見えるのは不親切なので一言伝える。
        if (!menuService.moveItem(id, up)) {
            redirectAttributes.addFlashAttribute("flashInfo",
                    up ? "このカテゴリの中では、すでにいちばん上です"
                       : "このカテゴリの中では、すでにいちばん下です");
        }
        return "redirect:/admin/items";
    }

    /**
     * 掲載を切り替える（一覧の「掲載」の列を押したとき）。
     *
     * <p>止めると、卓の QR から開くメニューに出なくなります。
     * カートに入っているぶんは {@code CartService#refresh} が落とします。
     *
     * <p>戻り先にタブと検索語を持ち帰ります。94 品ある画面なので、
     * 素の一覧に戻すと「掲載停止」タブで 1 品止めたとたんに
     * すべての品の一覧へ飛ばされ、続けて作業できません。
     */
    @PostMapping("/{id}/visibility")
    public String toggleVisibility(@PathVariable("id") Long id,
                                   @RequestParam(required = false) String tab,
                                   @RequestParam(required = false) String q,
                                   RedirectAttributes redirectAttributes) {
        try {
            boolean visible = menuService.toggleVisible(id);
            String name = menuItemRepository.findById(id).map(MenuItem::getName).orElse("商品");
            if (visible) {
                redirectAttributes.addFlashAttribute("flashSuccess",
                        "「%s」を掲載しました。各卓のメニューに出ます".formatted(name));
            } else {
                redirectAttributes.addFlashAttribute("flashInfo",
                        "「%s」の掲載を止めました。各卓のメニューから消えます".formatted(name));
            }
        } catch (MenuService.MenuItemNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(e.getMessage()));
        }
        return redirectToList(tab, q);
    }

    /**
     * 販売（品切れ）を切り替える（一覧の「販売」の列を押したとき）。
     *
     * <p><b>2026-08-27 に消した口を、2026-09-07 に戻したものです。</b>
     * 当時消した理由は 2 つで、いまはどちらも解けています。
     * <ul>
     *   <li>同じ値を切り替える口が 3 つあり、ここだけ他の部分集合だった
     *       → 一覧に「掲載」「販売」の列ができ、<b>状態を見ている場所と
     *       変える場所が同じ</b>になった。別の画面へ移動しなくてよい</li>
     *   <li>価格 0 円（時価）の品を再開すると ¥0 で注文できた
     *       → 危険そのものを {@code MenuService#toggleSoldOut} で閉じた。
     *       厨房の品切れパネルからも同じように守られる</li>
     * </ul>
     */
    @PostMapping("/{id}/sale")
    public String toggleSale(@PathVariable("id") Long id,
                             @RequestParam(required = false) String tab,
                             @RequestParam(required = false) String q,
                             RedirectAttributes redirectAttributes) {
        try {
            boolean soldOut = menuService.toggleSoldOut(id);
            String name = menuItemRepository.findById(id).map(MenuItem::getName).orElse("商品");
            if (soldOut) {
                redirectAttributes.addFlashAttribute("flashInfo",
                        "「%s」を品切れにしました。各卓のメニューから注文できなくなります".formatted(name));
            } else {
                redirectAttributes.addFlashAttribute("flashSuccess",
                        "「%s」の販売を再開しました".formatted(name));
            }
        } catch (MenuService.PriceNotSetException e) {
            // ここで断るのは意地悪ではなく、¥0 で売れてしまうのを止めるため。
            // 何をすれば直るかまで書く（この画面には価格の入力欄が無い）
            redirectAttributes.addFlashAttribute("flashErrors", List.of(
                    "「%s」は価格が入っていません。このまま販売を再開すると ¥0 で注文できてしまいます。先に商品名を押して、編集画面で価格を入れてください"
                            .formatted(e.getItemName())));
        } catch (MenuService.MenuItemNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(e.getMessage()));
        }
        return redirectToList(tab, q);
    }

    /** 押した場所（タブと検索語）へ戻す。 */
    private String redirectToList(String tab, String q) {
        StringBuilder url = new StringBuilder("redirect:/admin/items");
        String sep = "?";
        if (tab != null && !tab.isBlank()) {
            url.append(sep).append("tab=").append(UriUtils.encodeQueryParam(tab, StandardCharsets.UTF_8));
            sep = "&";
        }
        if (q != null && !q.isBlank()) {
            url.append(sep).append("q=").append(UriUtils.encodeQueryParam(q, StandardCharsets.UTF_8));
        }
        return url.toString();
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
