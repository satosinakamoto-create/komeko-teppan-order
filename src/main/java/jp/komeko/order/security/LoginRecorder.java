package jp.komeko.order.security;

import jp.komeko.order.service.StaffUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * ログインできたときに、その時刻をスタッフに書き残す。
 *
 * <p>設計 13 スタッフ（41:2170）の一覧に「最終ログイン」の列があります。
 * 辞めた人のアカウントが残っていないか、作ったまま使われていないアカウントが
 * 無いかを確かめるための情報です。
 *
 * <h2>なぜ SecurityConfig に書かないのか</h2>
 *
 * <p>{@code SecurityConfig} は認証の仕組みそのものを組み立てる設定クラスです。
 * そこへ {@link StaffUserService} を注ぎ込むと、
 * <b>「認証を組み立てるのに、認証で使うユーザーを読むサービスが要る」</b>という
 * 輪ができやすく、起動時の初期化順で転びます。
 *
 * <p>Spring Security はログインが通ると
 * {@link AuthenticationSuccessEvent} を投げてくれます。
 * 受け取る側を別の部品にしておけば、設定は設定のままでいられます。
 *
 * <h2>ここで転んでもログインは通す</h2>
 *
 * <p>記録に失敗しても例外を外へ出しません。
 * 最終ログインは<b>あとで見て役に立つ情報</b>であって、
 * 入れられないと働けないものではありません。
 * ここで転ぶと出勤して最初の 1 回でログインできず、店が開きません。
 */
@Component
public class LoginRecorder {

    private static final Logger log = LoggerFactory.getLogger(LoginRecorder.class);

    private final StaffUserService staffUserService;

    public LoginRecorder(StaffUserService staffUserService) {
        this.staffUserService = staffUserService;
    }

    @EventListener
    public void onLogin(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication().getName();
        try {
            // 見学用のゲストなど、staff_user に居ない相手は黙って素通りする
            staffUserService.recordLogin(username);
        } catch (RuntimeException e) {
            log.warn("最終ログインを記録できませんでした（ログインは通します）: {}", username, e);
        }
    }
}
