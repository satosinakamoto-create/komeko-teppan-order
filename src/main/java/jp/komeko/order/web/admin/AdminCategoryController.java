package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.MenuItemRepository;
import jp.komeko.order.service.MenuService;
import jp.komeko.order.web.admin.form.CategoryForm;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * カテゴリの管理（{@code /admin/categories}）。
 *
 * <p>1 画面のなかに「新規追加フォーム」と「行ごとの更新フォーム」が同居する、
 * 管理画面ではよくある形をしています。
 *
 * <p><b>なぜコントローラに {@code @Transactional} を付けているのか</b><br>
 * 本来この手の更新処理は Service 層（例：{@code CategoryService}）に置くのが定石です。
 * 「業務のルールは画面の都合から切り離す」ためで、そうすると
 * あとから API を生やしても同じ処理を使い回せます。
 * ここでは<b>他の担当者と同時に作業していて既存 Service を編集できない</b>という
 * プロジェクト都合から、例外的にコントローラのメソッドで完結させています。
 * 実務では Service に移してください。
 *
 * <p><b>{@code @Transactional} を付けると何が変わるか</b><br>
 * メソッドの開始から終了までが 1 つの「まとまった処理」になります。
 * その間 JPA が読み込んだエンティティは<b>管理された状態</b>になり、
 * setter で値を変えるだけでメソッド終了時に自動で UPDATE 文が飛びます
 * （ダーティチェック＝変更の自動検知）。
 * だから更新処理に {@code save()} の呼び出しが出てこないことがあります。
 */
@Controller
@RequestMapping("/admin/categories")
public class AdminCategoryController {

    private final CategoryRepository categoryRepository;
    private final MenuItemRepository menuItemRepository;
    private final MenuService menuService;
    private final MessageSource messageSource;

    public AdminCategoryController(CategoryRepository categoryRepository,
                                   MenuItemRepository menuItemRepository,
                                   MenuService menuService,
                                   MessageSource messageSource) {
        this.categoryRepository = categoryRepository;
        this.menuItemRepository = menuItemRepository;
        this.menuService = menuService;
        this.messageSource = messageSource;
    }

    // ========================================================================
    //  一覧＋新規フォーム
    // ========================================================================

    /**
     * カテゴリ一覧（読むだけ）。設計 09 カテゴリ（41:1782）。
     *
     * <p><b>2026-09-07 に、開いた直後を「読む画面」に変えました。</b><br>
     * それまでは開いた瞬間に新規追加フォームが出て、その下に
     * 1 行ずつ入力欄の付いた表が続いていました。
     * ところがこの画面を開く理由のほとんどは
     * 「いまカテゴリが何個あって、どれに何品入っているか」を見ることで、
     * <b>直すのはたまに</b>です。
     * 見るために開いたのに、いきなり書き換えられる画面が出るのは、
     * 押し間違いの的が常に置いてあるのと同じでした。
     *
     * <p>直すほうは {@link #edit} に分けました。
     */
    @GetMapping
    @Transactional(readOnly = true)
    public String index(Model model) {
        prepareList(model);
        return "admin/category-list";
    }

    /**
     * カテゴリを追加する画面（2026-09-20 に新設）。
     *
     * <p>商品（{@code /admin/items/new}）・卓（{@code /admin/tables/new}）と同じ形です。
     */
    @GetMapping("/new")
    @Transactional(readOnly = true)
    public String newForm(Model model) {
        model.addAttribute("categoryForm", new CategoryForm());
        model.addAttribute("groupNames", menuService.groupNames());
        // ★ 番兵はここから渡すこと。テンプレートで T(...) を使って定数を引くと、
        //   動いているアプリのクラスにまだ無いとき（Java を直して再起動する前）に
        //   式が落ち、画面が途中で切れます。エラー画面も出ません。
        model.addAttribute("newGroupSentinel", CategoryForm.NEW_GROUP);
        return "admin/category-new";
    }

    /**
     * カテゴリを 1 つだけ直す画面（2026-09-20 に新設）。
     *
     * <p><b>この画面の主役は「そのカテゴリに何が入っているか」です。</b>
     * カテゴリ名を直すのは付け足しなので、見出しの［名前を変える］に畳んであります。
     *
     * <p>できること: 名前を変える／商品をまとめて別のカテゴリへ移す／
     * 名前だけの商品を足す／（商品が 0 件なら）カテゴリを消す。
     *
     * <p><b>大分類はここでは変えられません。</b>店主の決定（2026-09-20）。
     * 大分類はお客さまのメニューのタブ名そのもの（{@link Category#getTabName()}）で、
     * 打ち間違えるとタブが割れるため、追加のときに選ぶ方式だけにしています。
     */
    @GetMapping("/{id}/edit")
    @Transactional(readOnly = true)
    public String editOne(@PathVariable("id") Long id, Model model,
                          RedirectAttributes redirectAttributes) {
        if (!prepareEditScreen(id, model)) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("カテゴリが見つかりませんでした（すでに削除された可能性があります）"));
            return "redirect:/admin/categories";
        }
        return "admin/category-edit";
    }

    // ========================================================================
    //  追加
    // ========================================================================

    /**
     * カテゴリを追加する。
     *
     * <p><b>引数の並び順に決まりがあります。</b>
     * {@code BindingResult} は必ず、検証対象の {@code @ModelAttribute} の
     * <b>すぐ後ろ</b>に書いてください。離すと Spring が結び付けを見失い、
     * 検証エラーの時点で例外になってしまいます。
     *
     * <p>{@code @Validated} を付けるとフォームクラスに書いた
     * {@code @NotBlank} などが実行され、違反は {@code BindingResult} に集まります。
     * エラーがあれば<b>リダイレクトせず</b>同じ画面を描き直します。
     * こうすると入力内容とエラーメッセージをそのまま出せます。
     */
    @PostMapping
    @Transactional
    public String create(@Validated @ModelAttribute("categoryForm") CategoryForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        // ★ 大分類の「＋ 新しい大分類を作る」を、ここで実際の名前に直します。
        //   直し忘れると Category.setGroupName が番兵をそのまま受け取り、
        //   @Size(max=20) を通ってしまうので例外も出ません。
        //   お客さまのメニューに __new__ というタブが出るまで誰も気づけません。
        String group = form.getGroupName();
        if (CategoryForm.NEW_GROUP.equals(group)) {
            String fresh = form.getNewGroupName();
            if (fresh == null || fresh.isBlank()) {
                binding.rejectValue("newGroupName", "required", "新しい大分類の名前を入力してください");
            } else {
                group = fresh.trim();
            }
        }

        if (binding.hasErrors()) {
            // ★ 選択肢と番兵を詰め直すこと。忘れると、戻ってきた画面の <select> が空になり、
            //   「＋ 新しい大分類を作る」の値も空になります。
            model.addAttribute("groupNames", menuService.groupNames());
            model.addAttribute("newGroupSentinel", CategoryForm.NEW_GROUP);
            return "admin/category-new";
        }

        // 並び順は聞かずに末尾へ付ける。
        // 既定の 0 のままだと、追加したカテゴリがメニューの先頭に割り込みます。
        // 順番を変えたくなったら、一覧でつまんで動かせます。
        Category category = new Category(form.getName().trim(), menuService.nextCategorySortOrder());
        category.setGroupName(group);   // 空白だけなら setter 側で未設定に揃える
        category.setVisible(true);      // 新しいカテゴリは表示中で入る（隠すのは一覧の札から）
        categoryRepository.save(category);

        // PRG（Post → Redirect → Get）。ここでリダイレクトしておかないと、
        // 保存後の画面で再読み込みされたときに同じ POST が飛んで二重登録になる。
        //
        // 戻り先は、いま作ったカテゴリの編集画面。
        // 「カテゴリを作る」の次にやりたいのは「中身を入れる」なので、その場所へ着地させます。
        redirectAttributes.addFlashAttribute("flashSuccess",
                "カテゴリ「%s」を追加しました。ここに商品を足せます".formatted(category.getName()));
        return "redirect:/admin/categories/" + category.getId() + "/edit";
    }

    // ========================================================================
    //  更新
    // ========================================================================

    /**
     * カテゴリ名を直す（編集画面の［名前を変える］から呼ばれる）。
     *
     * <h2>★ 名前しか書き換えません</h2>
     *
     * <p>この画面が送るのは {@code name} だけです。
     * <b>送られてこない項目を写すと、Form の初期値がそのまま DB へ行きます。</b>
     *
     * <pre>
     *   groupName … 送られてこない → null → 大分類が消えて、そのカテゴリだけ
     *               独立したタブに分裂する（Category.setGroupName が空白を null に寄せる）
     *   visible   … 初期値 true → 非表示のカテゴリが、名前を直しただけで表示に戻る
     *   sortOrder … 初期値 0 → 一覧の先頭へ飛ぶ
     * </pre>
     *
     * <p>{@code sortOrder} で 2026-09-19 に実際に踏みました。<b>同じ形です。</b>
     * どれも例外を出さず、次に画面を見るまで気づけません。
     * {@code @InitBinder} でも二重に塞いでいます。
     */
    @PostMapping("/{id}")
    @Transactional
    public String update(@PathVariable("id") Long id,
                         @Validated @ModelAttribute("editForm") CategoryForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        Category category = categoryRepository.findById(id).orElse(null);
        if (category == null) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("カテゴリが見つかりませんでした（すでに削除された可能性があります）"));
            return "redirect:/admin/categories";
        }

        // ★ 追加画面と同じく、「＋ 新しい大分類を作る」を実際の名前に直します。
        String group = form.getGroupName();
        if (CategoryForm.NEW_GROUP.equals(group)) {
            String fresh = form.getNewGroupName();
            if (fresh == null || fresh.isBlank()) {
                binding.rejectValue("newGroupName", "required", "新しい大分類の名前を入力してください");
            } else {
                group = fresh.trim();
            }
        }

        if (binding.hasErrors()) {
            prepareEditScreen(id, model);
            model.addAttribute("nameEditorOpen", true);   // 直している欄を開いたまま出す
            return "admin/category-edit";
        }

        // ここで setter を呼ぶだけで UPDATE 文が発行される（ダーティチェック）。
        // save() を書かなくてよいのは、このメソッドが @Transactional だから。
        category.setName(form.getName().trim());
        category.setGroupName(group);

        // ★ visible はここでも触りません。切り替えは一覧の札だけ（店主の決定）。
        //   写すと Form の初期値 true が書かれ、隠したカテゴリが名前を直しただけで戻ります。

        redirectAttributes.addFlashAttribute("flashSuccess",
                "カテゴリ「%s」を更新しました".formatted(category.getName()));
        return "redirect:/admin/categories/" + id + "/edit";
    }

    // ========================================================================
    //  削除
    // ========================================================================

    /**
     * カテゴリを削除する。
     *
     * <p>商品が 1 件でも残っているカテゴリは削除できません。
     * 商品は所属カテゴリが必須（{@code optional = false}）なので、
     * 消してしまうと行き場のない商品ができてしまうためです。
     *
     * <p><b>画面側でボタンを無効化していても、サーバ側の確認は必ず行います。</b>
     * URL を直接叩かれたら画面の制御はすり抜けてしまうからです。
     */
    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        Category category = categoryRepository.findById(id).orElse(null);
        if (category == null) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("カテゴリが見つかりませんでした（すでに削除された可能性があります）"));
            return "redirect:/admin/categories";
        }

        long itemCount = menuService.countItemsInCategory(id);
        if (itemCount > 0) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(
                    "カテゴリ「%s」には商品が %d 件あるため削除できません".formatted(category.getName(), itemCount),
                    "この画面の一覧で、商品を別のカテゴリへ移してください"));
            // ★ 断ったときは元の画面へ戻す。理由を読ませたい先に「移す」があるため。
            return "redirect:/admin/categories/" + id + "/edit";
        }

        String name = category.getName();
        categoryRepository.delete(category);
        redirectAttributes.addFlashAttribute("flashSuccess", "カテゴリ「%s」を削除しました".formatted(name));
        // 消した先の画面はもう無いので一覧へ
        return "redirect:/admin/categories";
    }

    // ========================================================================
    //  並び替え
    // ========================================================================

    /**
     * つまんで動かした結果を保存する（2026-09-19、店主の指示
     * 「商品、カテゴリー、卓にもドラッグ＆ドロップ実装してほしい」）。
     *
     * <p>行き先は「どの並びの隣か」で指します。{@code before} があればその直前、
     * 無ければ {@code after} の直後。商品の {@code /admin/items/place} と同じ形です。
     *
     * <p>画面は JavaScript から呼びますが<b>ふつうのフォーム送信</b>です。
     * テンプレートに隠しフォームを置いてあり（{@code th:action} なので
     * CSRF は Thymeleaf が入れる）、値を詰めて送るだけ。
     */
    @PostMapping("/place")
    public String place(@RequestParam Long id,
                        @RequestParam(required = false) Long before,
                        @RequestParam(required = false) Long after,
                        RedirectAttributes redirectAttributes) {
        if (!menuService.placeCategoryNextTo(id, before, after)) {
            redirectAttributes.addFlashAttribute("flashInfo", "並び順は変わりませんでした");
        }
        // ★ 戻り先は一覧（2026-09-19 に /edit から変更）。
        //   つまみが両方の画面にあった頃の名残で編集画面へ戻していましたが、
        //   並べ替えが一覧だけになった今、つまんだ瞬間に別の画面へ飛ばされます。
        //   動かした結果をその場で見せるのが正しい戻り先です。
        return "redirect:/admin/categories";
    }

    // ★ POST /{id}/move（上下ボタン）は 2026-09-20 に外しました。
    //   並べ替えは一覧のドラッグ＆ドロップ（/place）だけ、という決定のあと
    //   呼び出し元が 1 つも無くなっていました（テンプレート・JS・テストとも 0 件）。
    //   MenuService.moveCategory は MenuOrderingTest が直接呼んでいるので残してあります。

    // ========================================================================
    //  表示／非表示（一覧の札から）
    // ========================================================================

    /**
     * お客さまのメニューに出すかどうかを切り替える（2026-09-20）。
     *
     * <p><b>切り替え口はここだけです。</b>編集画面にチェックは置きません（店主の決定）。
     * 一覧の札をそのまま押せるようにしてあります（{@code .badge--act}）。
     *
     * <p>商品側の {@code /admin/items/{id}/visibility} と違い、
     * {@code tab} や {@code q} は受け取りません。カテゴリ一覧に絞り込みが無いためです。
     */
    @PostMapping("/{id}/visibility")
    @Transactional
    public String toggleVisibility(@PathVariable("id") Long id,
                                   RedirectAttributes redirectAttributes) {
        Category category = categoryRepository.findById(id).orElse(null);
        if (category == null) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("カテゴリが見つかりませんでした（すでに削除された可能性があります）"));
            return "redirect:/admin/categories";
        }
        category.setVisible(!category.isVisible());
        redirectAttributes.addFlashAttribute("flashSuccess", category.isVisible()
                ? "カテゴリ「%s」をお客さまのメニューに出しました".formatted(category.getName())
                : "カテゴリ「%s」を隠しました。中の商品もメニューから消えます"
                        .formatted(category.getName()));
        return "redirect:/admin/categories";
    }

    // ========================================================================
    //  そのカテゴリの商品を出し入れする（2026-09-20）
    // ========================================================================

    /**
     * 選んだ商品を、まとめて別のカテゴリへ移す。
     *
     * <p>★ {@code itemIds} は {@code required = false} にすること。
     * {@code true} だと、1 つも選ばずに［移す］を押した人に
     * 400 の白い画面が出ます。
     */
    @PostMapping("/{id}/items/move")
    @Transactional
    public String moveItems(@PathVariable("id") Long id,
                            @RequestParam(name = "itemIds", required = false) List<Long> itemIds,
                            @RequestParam("targetCategoryId") Long targetCategoryId,
                            RedirectAttributes redirectAttributes) {
        String back = "redirect:/admin/categories/" + id + "/edit";
        if (itemIds == null || itemIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("flashInfo",
                    "移す商品が選ばれていません。左のチェックを入れてから押してください");
            return back;
        }
        if (id.equals(targetCategoryId)) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("いま開いているカテゴリへは移せません"));
            return back;
        }
        Category target = categoryRepository.findById(targetCategoryId).orElse(null);
        if (target == null) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("移す先のカテゴリが見つかりませんでした"));
            return back;
        }

        int moved = menuService.moveItemsToCategory(itemIds, targetCategoryId);
        redirectAttributes.addFlashAttribute("flashSuccess",
                "%d 品を「%s」へ移しました".formatted(moved, target.getName()));
        return back;
    }

    /**
     * 名前だけで商品を足す（価格はあとから商品の画面で入れる）。
     *
     * <h2>★ 同じ名前があったら作らない</h2>
     *
     * <p>商品名には一意制約も重複チェックもありません（食材にはあります）。
     * 「鉄板麺に焼きそばがある」と知らずにここで打つと、
     * <b>黙って 2 つ目の書きかけができます。</b>
     * 気づけるのは商品一覧を見たときです。
     *
     * <p>そこで、同名を見つけたら<b>作らずに止めて</b>、
     * その商品がいまどこにいるかを知らせ、［ここへ移す］を出します。
     * 入口を 1 つに保ったまま、重複も防げて「引き寄せたい」も叶います。
     *
     * <p>★ 止めるときは 3xx ではなく 200 で描き直します。
     * 重複商品の id・名前・所属カテゴリ名の 3 つを持ち回る必要があり、
     * フラッシュ属性だと F5 で消えるためです。
     * 検証エラーで 200 を返すのは、このコントローラの {@code create} と同じ流儀です。
     */
    @PostMapping("/{id}/items")
    @Transactional
    public String addItem(@PathVariable("id") Long id,
                          @RequestParam("name") String name,
                          Model model,
                          RedirectAttributes redirectAttributes) {
        String back = "redirect:/admin/categories/" + id + "/edit";
        if (name == null || name.isBlank()) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of("商品名を入力してください"));
            return back;
        }

        MenuItem same = menuService.findSameNameItem(name);
        if (same != null) {
            prepareEditScreen(id, model);
            // ★ flashErrors とは別の名前にすること。
            //   flashErrors は fragments/common が .alert--error で出すので、帯が 2 つ出ます。
            model.addAttribute("duplicateItem", same);
            model.addAttribute("duplicateItemHere", id.equals(same.getCategory().getId()));
            model.addAttribute("addItemName", name.trim());
            return "admin/category-edit";
        }

        MenuItem created = menuService.createDraftItem(id, name);
        redirectAttributes.addFlashAttribute("flashSuccess",
                "「%s」を書きかけで足しました。価格を入れると掲載できます".formatted(created.getName()));
        return back;
    }

    // ========================================================================
    //  画面を描くための共通処理
    // ========================================================================

    /**
     * 一覧画面に必要な値をモデルへ詰める。
     *
     * <p>カテゴリごとの商品数は 1 件ずつ数えています（カテゴリ数だけ SQL が飛びます）。
     * カテゴリは多くても十数件なので実用上は問題ありませんが、
     * 何百件も並ぶ画面では「まとめて数える」クエリを用意すべきところです。
     */
    /**
     * 編集画面（1 カテゴリぶん）に必要な値をモデルへ詰める。
     *
     * <p><b>★ 画面を描く経路は必ずここを通すこと。</b>
     * {@code GET /{id}/edit} と「同名で止めたとき」の 2 つが描きます。
     * それぞれで詰めると、片方だけ詰め忘れて画面が半分空になります。
     *
     * <p>{@code open-in-view: false} なので、画面で使う値はここで読み切ります。
     *
     * @return 見つかれば true
     */
    private boolean prepareEditScreen(Long id, Model model) {
        Category category = categoryRepository.findById(id).orElse(null);
        if (category == null) {
            return false;
        }
        List<MenuItem> items = menuItemRepository.findByCategoryIdOrderBySortOrderAscIdAsc(id);

        model.addAttribute("category", category);
        model.addAttribute("items", items);
        model.addAttribute("itemCount", items.size());
        // 大分類の選択肢（2026-09-20 夕に編集画面でも選べるようにした）。
        // ★ いま付いている値が一覧に無いことがあります（昔の自由入力で入った値）。
        //   その場合でも選び直せるよう、テンプレート側で今の値を先頭に足しています。
        model.addAttribute("groupNames", menuService.groupNames());
        model.addAttribute("newGroupSentinel", CategoryForm.NEW_GROUP);
        // 掲載中／書きかけの内訳（節見出しの補足に出す）
        model.addAttribute("publishedCount", items.stream().filter(i -> !i.isDraft()).count());
        model.addAttribute("draftCount", items.stream().filter(MenuItem::isDraft).count());
        // 行き先の候補。自分自身は出さない（選べても何も起きないため）
        model.addAttribute("moveTargets", categoryRepository.findAllByOrderBySortOrderAscIdAsc()
                .stream().filter(c -> !c.getId().equals(id)).toList());
        if (!model.containsAttribute("editForm")) {
            model.addAttribute("editForm", CategoryForm.of(category));
        }
        return true;
    }

    /**
     * 新規追加のフォームで受け取らない項目（2026-09-20）。
     *
     * <p>★ モデル属性名を必ず指定すること。書かないと、同じコントローラにいる
     * {@code editForm} まで塞いでしまいます。
     */
    @InitBinder("categoryForm")
    void restrictCreate(WebDataBinder binder) {
        binder.setDisallowedFields("id", "visible");
    }

    /**
     * 名前を変えるフォームで受け取らない項目（2026-09-20）。
     *
     * <p>この画面が送るのは {@code name} と {@code groupName} だけです。
     * 表示／非表示は一覧の札からしか変えられません。
     * URL を手で組んで送りつけられても、ここで落ちます。
     *
     * <h2>★ 大分類は一度「変えられない」にして、同じ日に戻しました</h2>
     *
     * <p>閉じた理由は「打ち間違えるとお客さまのメニューのタブが割れる」でした。
     * ところが<b>同じ日に自由入力をやめて選ぶ方式にした</b>ので、
     * 打ち間違いはもう起きません。閉じる理由のほうが先に消えていました。
     *
     * <p>閉じたままだと実害が出ます。実際、この DB には
     * 「広島風お好み焼き → 大分類 お好み焼き」のように
     * <b>カテゴリ名とほぼ同じ大分類</b>が 2 件入っていて、直す手段がありませんでした。
     */
    @InitBinder("editForm")
    void restrictUpdate(WebDataBinder binder) {
        binder.setDisallowedFields("id", "visible");
    }

    private void prepareList(Model model) {
        List<Category> categories = categoryRepository.findAllByOrderBySortOrderAscIdAsc();

        // LinkedHashMap は「入れた順番」を保つ Map。並び順が意味を持つ画面では必須。
        Map<Long, Long> itemCounts = new LinkedHashMap<>();
        for (Category category : categories) {
            itemCounts.put(category.getId(), menuService.countItemsInCategory(category.getId()));
        }

        model.addAttribute("categories", categories);
        model.addAttribute("itemCounts", itemCounts);
    }

    /**
     * 検証エラーを「項目名 → メッセージ」の Map に変換する。
     *
     * <p>テンプレートで {@code ${editErrors.get('name')}} のように引ける形にして、
     * 行単位のフォームでもフィールド単位のエラー表示ができるようにするためのヘルパーです。
     * 同じ項目に複数のエラーが付いた場合は最初の 1 件だけを残します
     * （画面が長くなりすぎないようにするため）。
     */
    private Map<String, String> fieldErrors(BindingResult binding) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : binding.getFieldErrors()) {
            errors.putIfAbsent(error.getField(), resolveMessage(error));
        }
        return errors;
    }

    /**
     * エラーを画面に出す日本語の文言に直す。
     *
     * <p><b>{@code error.getDefaultMessage()} をそのまま使ってはいけない理由</b><br>
     * 数値欄に文字を入れられた場合（型変換の失敗）、既定メッセージは
     * 「Failed to convert property value of type 'java.lang.String' to ...」という
     * Spring 内部の英語文になります。これをそのまま出すと、
     * 日本語の画面に英語が混じるうえ、内部のクラス名まで見えてしまいます。
     *
     * <p>{@code th:errors} は内部で MessageSource を通しているので、
     * 手作りのエラー表示でも同じように通します。こうすると messages.properties の
     * {@code typeMismatch.java.lang.Integer=数値を入力してください} が効き、
     * 画面全体で文言がそろいます。
     * アノテーションに {@code message} を書いた項目は、そのまま既定メッセージが使われます。
     */
    private String resolveMessage(FieldError error) {
        try {
            return messageSource.getMessage(error, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            // メッセージ定義も既定メッセージも無い、という想定外のときの保険
            return error.getDefaultMessage();
        }
    }
}
