package jp.komeko.order.web.admin;

import jakarta.validation.Valid;
import jp.komeko.order.domain.StaffRole;
import jp.komeko.order.domain.StaffUser;
import jp.komeko.order.security.StaffUserDetails;
import jp.komeko.order.service.StaffUserService;
import jp.komeko.order.web.admin.form.StaffForm;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

/**
 * スタッフアカウントの管理画面（管理者のみ）。
 *
 * <p>この画面は「自分の首を絞められる」数少ない画面です。
 * 最後の管理者を無効化・削除してしまうと、二度と管理画面に入れなくなります。
 * そのため
 * <ul>
 *   <li>削除は {@link StaffUserService#delete(Long)} が管理者 0 人を拒否する</li>
 *   <li>権限変更・無効化は、このコントローラでも同じ観点で歯止めをかける</li>
 * </ul>
 * という二重の守りを入れています。
 *
 * <p><b>例外の扱い方</b><br>
 * サービスが投げる {@code IllegalArgumentException}（入力が不正）と
 * {@code IllegalStateException}（今はその操作をしてはいけない）は、
 * ここで受け止めて {@code flashErrors} に載せ、一覧画面へリダイレクトします。
 * 受け止めずに素通しすると {@code GlobalExceptionHandler} が拾って
 * 全画面のエラーページに飛んでしまい、「管理者が 0 人になるため削除できません」という
 * せっかくの親切なメッセージが一覧画面で読めなくなるためです。
 */
@Controller
@RequestMapping("/admin/staff")
public class AdminStaffController {

    private final StaffUserService staffUserService;

    public AdminStaffController(StaffUserService staffUserService) {
        this.staffUserService = staffUserService;
    }

    // ========================================================================
    //  一覧＋新規登録フォーム
    // ========================================================================

    /**
     * スタッフ一覧と新規登録フォームを表示する。
     *
     * <p>{@code @AuthenticationPrincipal} を引数に付けると、
     * いまログインしている人の情報（{@link StaffUserDetails}）が渡ってきます。
     * 「この行はあなた自身です」と画面に出すために使います。
     */
    @GetMapping
    public String list(@AuthenticationPrincipal StaffUserDetails me, Model model) {
        prepare(model, me);
        model.addAttribute("staffForm", new StaffForm());
        return "admin/staff";
    }

    /**
     * スタッフを新規登録する。
     *
     * <p>{@code @Valid} + {@link BindingResult} の組み合わせがポイントです。
     * {@code @Valid} を付けると Spring が {@link StaffForm} のアノテーションを検査し、
     * 結果を直後の引数 {@code BindingResult} に入れてくれます。
     * <b>BindingResult は必ず検証対象の直後に置く</b>のがルールで、
     * 順番を入れ替えると例外になります。
     *
     * <p>入力エラーのときはリダイレクトせずに一覧画面を描き直します
     * （入力値とエラー箇所を残すため）。一覧のデータは消えているので詰め直しが必要です。
     */
    @PostMapping
    public String create(@Valid @ModelAttribute("staffForm") StaffForm staffForm,
                         BindingResult bindingResult,
                         @AuthenticationPrincipal StaffUserDetails me,
                         Model model,
                         RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            prepare(model, me);
            return "admin/staff";
        }

        try {
            StaffUser created = staffUserService.create(
                    staffForm.getUsername().trim(),
                    staffForm.getPassword(),
                    staffForm.getDisplayName().trim(),
                    staffForm.getRole());
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "スタッフ「%s」を追加しました".formatted(created.getDisplayName()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 「そのユーザー名は既に使われています」などはサービスが日本語で投げてくれる
            redirectAttributes.addFlashAttribute("flashErrors", List.of(e.getMessage()));
        }
        return "redirect:/admin/staff";
    }

    // ========================================================================
    //  1 件ごとの操作
    // ========================================================================

    /**
     * 表示名・権限・有効/無効を更新する。
     *
     * <p>チェックボックスは「チェックが外れているとブラウザが何も送らない」ため、
     * {@code defaultValue = "false"} を付けておかないと 400 エラーになります。
     * よくある落とし穴なので覚えておいてください。
     */
    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) String displayName,
                         @RequestParam StaffRole role,
                         @RequestParam(defaultValue = "false") boolean enabled,
                         RedirectAttributes redirectAttributes) {

        List<String> errors = new ArrayList<>();
        String name = (displayName == null) ? "" : displayName.trim();
        if (name.isEmpty()) {
            errors.add("表示名を入力してください");
        } else if (name.length() > 40) {
            errors.add("表示名は40文字以内で入力してください");
        }

        if (errors.isEmpty()) {
            try {
                ensureAdminRemains(id, role, enabled);
                staffUserService.update(id, name, role, enabled);
                redirectAttributes.addFlashAttribute("flashSuccess",
                        "スタッフ「%s」を更新しました".formatted(name));
            } catch (IllegalArgumentException | IllegalStateException e) {
                errors.add(e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            redirectAttributes.addFlashAttribute("flashErrors", errors);
        }
        return "redirect:/admin/staff";
    }

    /**
     * パスワードを変更する。
     *
     * <p>長さの検査はサービス（{@code StaffUserService#changePassword}）が行い、
     * 短すぎれば日本語のメッセージ付きで例外を投げてくれます。
     * 同じ検査をコントローラにも書くと、片方だけ直して食い違う原因になるので、
     * <b>業務ルールの検査はサービス 1 箇所</b>に任せます。
     */
    @PostMapping("/{id}/password")
    public String changePassword(@PathVariable Long id,
                                 @RequestParam(required = false) String password,
                                 RedirectAttributes redirectAttributes) {
        try {
            staffUserService.changePassword(id, password);
            redirectAttributes.addFlashAttribute("flashSuccess", "パスワードを変更しました");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(e.getMessage()));
        }
        return "redirect:/admin/staff";
    }

    /**
     * スタッフを削除する。
     *
     * <p>削除できるかどうかの判断（管理者 0 人ガード）はサービス側にあります。
     * ここでは例外を受け止めて画面に出すだけです。
     */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal StaffUserDetails me,
                         RedirectAttributes redirectAttributes) {
        try {
            // 削除後には名前が取れないので、メッセージ用に先に読んでおく
            StaffUser target = staffUserService.getById(id);
            staffUserService.delete(id);
            redirectAttributes.addFlashAttribute("flashSuccess",
                    "スタッフ「%s」を削除しました".formatted(target.getDisplayName()));

            if (me != null && id.equals(me.getId())) {
                redirectAttributes.addFlashAttribute("flashInfo",
                        "いま使っているアカウントを削除しました。ログアウトすると、二度とこのアカウントでは入れません。");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashErrors", List.of(e.getMessage()));
        }
        return "redirect:/admin/staff";
    }

    // ========================================================================
    //  内部ヘルパー
    // ========================================================================

    /** 一覧画面を描くのに必要なものをまとめてモデルへ。 */
    private void prepare(Model model, StaffUserDetails me) {
        model.addAttribute("activeNav", "admin");
        model.addAttribute("staffList", staffUserService.findAll());
        model.addAttribute("roles", StaffRole.values());
        model.addAttribute("adminCount", staffUserService.countEnabledAdmins());
        // 「（ログイン中）」の目印を付けるために、自分の id を渡す
        model.addAttribute("currentStaffId", me == null ? null : me.getId());
    }

    /**
     * 有効な管理者が 0 人にならないことを確かめる。
     *
     * <p>{@link StaffUserService#delete(Long)} には同じ趣旨のガードがありますが、
     * {@code update} には無いため「最後の管理者を無効化してログインできなくなる」道が
     * 残っています。本来はサービス側に置きたいルールですが、
     * 今回はサービスを編集できないので、
     * 既にある public メソッド（{@code getById} と {@code countEnabledAdmins}）を
     * 組み合わせて、この画面から入る操作だけは塞いでいます。
     */
    private void ensureAdminRemains(Long id, StaffRole newRole, boolean newEnabled) {
        StaffUser target = staffUserService.getById(id);

        boolean wasActiveAdmin = target.getRole() == StaffRole.ADMIN && target.isEnabled();
        boolean willBeActiveAdmin = newRole == StaffRole.ADMIN && newEnabled;

        if (wasActiveAdmin && !willBeActiveAdmin && staffUserService.countEnabledAdmins() <= 1) {
            throw new IllegalStateException(
                    "有効な管理者が 0 人になるため、この変更はできません。先に別の管理者を追加してください");
        }
    }
}
