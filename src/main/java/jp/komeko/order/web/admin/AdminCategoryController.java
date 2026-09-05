package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Category;
import jp.komeko.order.repository.CategoryRepository;
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
import org.springframework.web.bind.annotation.GetMapping;
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
    private final MenuService menuService;
    private final MessageSource messageSource;

    public AdminCategoryController(CategoryRepository categoryRepository,
                                   MenuService menuService,
                                   MessageSource messageSource) {
        this.categoryRepository = categoryRepository;
        this.menuService = menuService;
        this.messageSource = messageSource;
    }

    // ========================================================================
    //  一覧＋新規フォーム
    // ========================================================================

    /**
     * カテゴリ一覧。画面上部に新規追加フォーム、各行に更新・削除フォームを出す。
     */
    @GetMapping
    @Transactional(readOnly = true)
    public String index(Model model) {
        prepareList(model);
        model.addAttribute("categoryForm", new CategoryForm());
        return "admin/categories";
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
        if (binding.hasErrors()) {
            prepareList(model);
            return "admin/categories";
        }

        // 並び順は聞かずに末尾へ付ける。
        // 既定の 0 のままだと、追加したカテゴリがメニューの先頭に割り込みます。
        // 順番を変えたくなったら、一覧の上下ボタンで動かせます。
        Category category = new Category(form.getName().trim(), menuService.nextCategorySortOrder());
        category.setGroupName(form.getGroupName());   // 空白だけなら setter 側で未設定に揃える
        category.setVisible(form.isVisible());
        categoryRepository.save(category);

        // PRG（Post → Redirect → Get）。ここでリダイレクトしておかないと、
        // 保存後の画面で再読み込みされたときに同じ POST が飛んで二重登録になる。
        redirectAttributes.addFlashAttribute("flashSuccess",
                "カテゴリ「%s」を追加しました".formatted(category.getName()));
        return "redirect:/admin/categories";
    }

    // ========================================================================
    //  更新
    // ========================================================================

    /**
     * カテゴリを更新する（一覧の各行にあるフォームから呼ばれる）。
     *
     * <p><b>1 画面に同じ形のフォームが何個もある場合の考え方</b><br>
     * {@code BindingResult} は 1 リクエストにつき 1 つのフォームぶんしか持てません。
     * 一覧の全行に {@code th:object} を割り当てることはできないので、
     * 「どの行でエラーが起きたか（{@code editingId}）」と
     * 「項目名 → メッセージの対応表（{@code editErrors}）」を
     * モデルに入れて、テンプレート側でその行だけエラー表示する形にしています。
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

        if (binding.hasErrors()) {
            prepareList(model);
            // 新規追加フォームは空のまま出したいので、別インスタンスを入れておく
            model.addAttribute("categoryForm", new CategoryForm());
            model.addAttribute("editingId", id);
            model.addAttribute("editErrors", fieldErrors(binding));
            return "admin/categories";
        }

        // ここで setter を呼ぶだけで UPDATE 文が発行される（ダーティチェック）。
        // save() を書かなくてよいのは、このメソッドが @Transactional だから。
        category.setName(form.getName().trim());
        category.setGroupName(form.getGroupName());   // 空白だけなら setter 側で未設定に揃える
        category.setSortOrder(form.getSortOrder());
        category.setVisible(form.isVisible());

        redirectAttributes.addFlashAttribute("flashSuccess",
                "カテゴリ「%s」を更新しました".formatted(category.getName()));
        return "redirect:/admin/categories";
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
                    "商品を別のカテゴリに移すか、先に商品を削除してください"));
            return "redirect:/admin/categories";
        }

        String name = category.getName();
        categoryRepository.delete(category);
        redirectAttributes.addFlashAttribute("flashSuccess", "カテゴリ「%s」を削除しました".formatted(name));
        return "redirect:/admin/categories";
    }

    // ========================================================================
    //  並び替え
    // ========================================================================

    /**
     * カテゴリをひとつ上（または下）へ動かす。
     *
     * <p><b>数字を入れる欄より、上下のボタンのほうが速い。</b>
     * 「お飲み物を鉄板料理の下に持ってきたい」と思ったとき、
     * 数字での指定は、いまの数字を全部読んで、間に入る値を考えて、
     * 入力して、並びを見て確かめる、という手順になります。
     * 上下ボタンなら押した回数ぶんだけ動きます。
     *
     * <p>数字の欄は各行の編集フォームに残してあります。
     * 「まとめて並べ直す」ときはそちらのほうが速いためです。
     */
    @PostMapping("/{id}/move")
    public String move(@PathVariable("id") Long id,
                       @RequestParam boolean up,
                       RedirectAttributes redirectAttributes) {
        // 端まで来ていたら何も起きない。押しても無反応に見えるのは不親切なので、
        // 動かなかったことを一言伝える。
        if (!menuService.moveCategory(id, up)) {
            redirectAttributes.addFlashAttribute("flashInfo",
                    up ? "すでにいちばん上です" : "すでにいちばん下です");
        }
        return "redirect:/admin/categories";
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
