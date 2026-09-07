package jp.komeko.order.web.admin;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.service.TableService;
import jp.komeko.order.web.admin.form.TableForm;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卓（テーブル）の管理（{@code /admin/tables}）。
 *
 * <p>イートインのモバイルオーダーでは、卓ごとに固定の QR コードを貼ります。
 * ここは「店にどんな席があるか」を登録する画面で、
 * ここで作った卓の数だけ QR が生まれます（印刷は {@code /admin/qr}）。
 *
 * <p><b>この画面で気をつけること</b>
 * <ul>
 *   <li><b>QR の再発行は取り返しがつきません。</b>
 *       トークンを振り直すので、その卓に貼ってある QR は読めなくなります。
 *       印刷して貼り直すまで、その席からは注文できません。</li>
 *   <li><b>過去の伝票がある卓は削除できません。</b>
 *       消してしまうと売上の記録から「どの席の会計だったか」が失われるためです。
 *       席を畳んだときは削除ではなく「利用停止」にします。</li>
 * </ul>
 *
 * <p><b>なぜコントローラに {@code @Transactional} が無いのか</b><br>
 * この画面の処理はすべて {@link TableService} のメソッドを呼ぶだけで、
 * トランザクション（＝ひとまとまりの DB 操作）の管理は Service 側が持っています。
 * 「業務のルールは Service、画面の都合は Controller」と分けておくと、
 * あとから同じ処理を API から呼びたくなったときにそのまま使い回せます。
 */
@Controller
@RequestMapping("/admin/tables")
public class AdminTableController {

    private final TableService tableService;
    private final MessageSource messageSource;

    public AdminTableController(TableService tableService, MessageSource messageSource) {
        this.tableService = tableService;
        this.messageSource = messageSource;
    }

    // ========================================================================
    //  一覧＋新規フォーム
    // ========================================================================

    /** 卓の一覧。画面上部に新規追加フォーム、各行に更新・再発行・削除のフォームを出す。 */
    @GetMapping
    public String index(Model model) {
        prepareList(model);
        model.addAttribute("tableForm", new TableForm());
        return "admin/tables";
    }

    // ========================================================================
    //  追加
    // ========================================================================

    /**
     * 卓を追加する。
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
     *
     * <p><b>「使う」のチェックについて</b><br>
     * {@link TableService#createTable(String, int, int)} は卓を必ず「稼働中」で作ります
     * （{@link DiningTable} の {@code active} の初期値が true）。
     * そのため、チェックを外して追加されたときだけ、作った直後に更新をかけて
     * 利用停止に落とします。ここを省くと、画面では「使わない」と指定したのに
     * 卓が稼働中で登録され、貼っていない QR から注文が入りうる状態になります。
     */
    @PostMapping
    public String create(@Validated @ModelAttribute("tableForm") TableForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        if (binding.hasErrors()) {
            prepareList(model);
            return "admin/tables";
        }

        String name = form.getName().trim();
        try {
            DiningTable created = tableService.createTable(
                    name, form.getCapacity(), form.getSortOrder(), form.getArea());
            if (!form.isActive()) {
                tableService.updateTable(created.getId(), name,
                        form.getCapacity(), form.getSortOrder(), false, form.getArea());
            }
        } catch (IllegalArgumentException e) {
            // 卓名の重複。画面全体の警告ではなく、原因になった入力欄の下に出したいので
            // rejectValue で「name 欄のエラー」として BindingResult に積み直す。
            binding.rejectValue("name", "duplicate", e.getMessage());
            prepareList(model);
            return "admin/tables";
        }

        // PRG（Post → Redirect → Get）。ここでリダイレクトしておかないと、
        // 保存後の画面で再読み込みされたときに同じ POST が飛んで二重登録になる。
        redirectAttributes.addFlashAttribute("flashSuccess", form.isActive()
                ? "卓「%s」を追加しました。QR は「QRコード」の画面から印刷してください".formatted(name)
                : "卓「%s」を利用停止の状態で追加しました。使うときは「使う」にチェックを入れて更新してください"
                        .formatted(name));
        return "redirect:/admin/tables";
    }

    // ========================================================================
    //  更新
    // ========================================================================

    /**
     * 卓を更新する（一覧の各行にあるフォームから呼ばれる）。
     *
     * <p><b>1 画面に同じ形のフォームが何個もある場合の考え方</b><br>
     * {@code BindingResult} は 1 リクエストにつき 1 つのフォームぶんしか持てません。
     * 一覧の全行に {@code th:object} を割り当てることはできないので、
     * 「どの行でエラーが起きたか（{@code editingId}）」と
     * 「項目名 → メッセージの対応表（{@code editErrors}）」を
     * モデルに入れて、テンプレート側でその行だけエラー表示する形にしています。
     */
    @PostMapping("/{id}")
    public String update(@PathVariable("id") Long id,
                         @Validated @ModelAttribute("editForm") TableForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        if (binding.hasErrors()) {
            prepareList(model);
            // 新規追加フォームは空のまま出したいので、別インスタンスを入れておく
            model.addAttribute("tableForm", new TableForm());
            model.addAttribute("editingId", id);
            model.addAttribute("editErrors", fieldErrors(binding));
            return "admin/tables";
        }

        String name = form.getName().trim();
        try {
            tableService.updateTable(id, name, form.getCapacity(), form.getSortOrder(),
                    form.isActive(), form.getArea());
        } catch (TableService.TableNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("卓が見つかりませんでした（すでに削除された可能性があります）"));
            return "redirect:/admin/tables";
        } catch (IllegalArgumentException e) {
            // 卓名の重複。行ごとのフォームなので、ここは画面上部のエラー表示に回す。
            redirectAttributes.addFlashAttribute("flashErrors", List.of(e.getMessage()));
            return "redirect:/admin/tables";
        }

        redirectAttributes.addFlashAttribute("flashSuccess", "卓「%s」を更新しました".formatted(name));
        return "redirect:/admin/tables";
    }

    // ========================================================================
    //  削除
    // ========================================================================

    /**
     * 卓を削除する。
     *
     * <p>過去の伝票が 1 件でも紐づいている卓は削除できません
     * （{@link TableService#deleteTable(Long)} が {@link IllegalStateException} を投げます）。
     *
     * <p><b>なぜここで例外を捕まえるのか</b><br>
     * {@code IllegalStateException} を素通しすると、共通の例外ハンドラ
     * （{@code GlobalExceptionHandler}）が拾ってエラーページ（409）に飛ばしてしまいます。
     * 「削除できない理由を一覧画面で読ませて、そのまま利用停止に切り替えてもらう」ほうが
     * 店の人にとっては親切なので、ここで受け止めてフラッシュメッセージに変換します。
     */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        String name;
        try {
            name = tableService.getById(id).getName();
        } catch (TableService.TableNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("卓が見つかりませんでした（すでに削除された可能性があります）"));
            return "redirect:/admin/tables";
        }

        try {
            tableService.deleteTable(id);
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(
                    e.getMessage(),
                    "売上や注文履歴から「どの席の会計だったか」が消えてしまうため、削除はできません"));
            return "redirect:/admin/tables";
        }

        redirectAttributes.addFlashAttribute("flashSuccess", "卓「%s」を削除しました".formatted(name));
        return "redirect:/admin/tables";
    }

    // ========================================================================
    //  QR の再発行
    // ========================================================================

    /**
     * QR コードを作り直す（トークンを振り直す）。
     *
     * <p>実行した瞬間から、その卓に貼ってある QR は読めなくなります。
     * 新しい QR を印刷して貼り替えるまで、その席からは注文できません。
     * 取り返しがつかない操作なので、画面側では {@code confirm()} で
     * 必ず一度確認してから送信するようにしています。
     *
     * <p>それでも「画面で確認しているからサーバは何もしなくてよい」わけではありません。
     * URL を直接叩けば画面の確認はすり抜けられます。ここでは
     * <b>POST でしか呼べないようにする</b>ことで、
     * リンクを踏んだだけ・ブラウザが先読みしただけで実行されることを防いでいます。
     */
    @PostMapping("/{id}/regenerate")
    public String regenerate(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        String name;
        try {
            name = tableService.getById(id).getName();
            tableService.regenerateToken(id);
        } catch (TableService.TableNotFoundException e) {
            redirectAttributes.addFlashAttribute("flashErrors",
                    List.of("卓が見つかりませんでした（すでに削除された可能性があります）"));
            return "redirect:/admin/tables";
        }

        redirectAttributes.addFlashAttribute("flashInfo",
                "卓「%s」の QR を再発行しました。古い QR はもう使えません。「QRコード」の画面から印刷して貼り替えてください"
                        .formatted(name));
        return "redirect:/admin/tables";
    }

    // ========================================================================
    //  画面を描くための共通処理
    // ========================================================================

    /** 一覧画面に必要な値をモデルへ詰める。 */
    private void prepareList(Model model) {
        List<DiningTable> tables = tableService.allTables();

        model.addAttribute("tables", tables);
        // 席数の合計。「店全体で何席あるか」がひと目で分かると、卓の作り忘れに気付きやすい。
        model.addAttribute("totalSeats", tables.stream().mapToInt(DiningTable::getCapacity).sum());
        model.addAttribute("activeCount", tables.stream().filter(DiningTable::isActive).count());
    }

    /**
     * 検証エラーを「項目名 → メッセージ」の Map に変換する。
     *
     * <p>テンプレートで {@code ${editErrors.get('name')}} のように引ける形にして、
     * 行単位のフォームでもフィールド単位のエラー表示ができるようにするためのヘルパーです。
     * 同じ項目に複数のエラーが付いた場合は最初の 1 件だけを残します
     * （画面が長くなりすぎないようにするため）。
     *
     * <p>{@code LinkedHashMap} は「入れた順番」を保つ Map です。
     * ふつうの {@code HashMap} だと順番が保証されないので、
     * 表示順が意味を持つ場面ではこちらを使います。
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
