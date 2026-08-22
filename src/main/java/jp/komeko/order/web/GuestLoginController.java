package jp.komeko.order.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.komeko.order.domain.StaffRole;
import jp.komeko.order.domain.StaffUser;
import jp.komeko.order.repository.StaffUserRepository;
import jp.komeko.order.security.StaffUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * ポートフォリオ用の「ゲストとして見る」ログイン。
 *
 * <p>採用担当の方に触ってもらうためのものです。
 * ログイン画面のボタンを 1 つ押すだけで、
 * <b>スタッフ権限</b>で厨房ボードとホール画面に入れます。
 *
 * <p><b>なぜ管理者アカウントを使わせないのか</b><br>
 * 管理者はメニューの価格・売上・スタッフのパスワードまで触れます。
 * デモで見てほしいのは「注文が厨房に届き、状態を進めるとお客さまの画面が変わる」ところなので、
 * それ以上の権限を渡す理由がありません。
 * ゲストは {@link StaffRole#STAFF} なので、{@code /admin/**} には入れません
 * （{@code SecurityConfig} が ADMIN 限定にしています）。
 *
 * <p>「注文を進める」「キャンセルする」はできます。<b>これは意図的です。</b>
 * 押してもらえないと連携が体感できません。荒らされても定期的に作り直せば戻ります。
 *
 * <p><b>安全装置</b><br>
 * {@code app.guest-login=true} のときだけ、このクラスが読み込まれます。
 * 既定は false なので、<b>実店舗の画面にゲストのボタンは出ません</b>。
 * 公開デモの環境変数にだけ {@code APP_GUEST_LOGIN=true} を入れて使います。
 */
@Controller
@ConditionalOnProperty(name = "app.guest-login", havingValue = "true")
public class GuestLoginController {

    private static final Logger log = LoggerFactory.getLogger(GuestLoginController.class);

    /** ゲストのユーザー名。実在のスタッフと衝突しない名前にしておく。 */
    public static final String GUEST_USERNAME = "guest";

    private final StaffUserRepository staffUserRepository;
    private final String guestDisplayName;

    /**
     * ログイン状態をセッションへ保存する担当。
     *
     * <p>Spring Security 6 からは、プログラムでログインさせるとき
     * <b>自分でセッションに書き込む必要があります</b>。
     * {@code SecurityContextHolder} に入れるだけだと、
     * そのリクエストの間しか有効ではなく、次のページを開いた瞬間にログイン画面へ戻されます。
     * 以前のバージョンでは自動で保存されていたので、ここは移行時に踏みやすい落とし穴です。
     */
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public GuestLoginController(StaffUserRepository staffUserRepository,
                                @Value("${app.guest-display-name:デモ見学者}") String guestDisplayName) {
        this.staffUserRepository = staffUserRepository;
        this.guestDisplayName = guestDisplayName;
    }

    /**
     * ゲストとしてログインする。
     *
     * <p><b>POST にしている理由</b><br>
     * リンク（GET）にすると、外部のサイトに
     * {@code <img src="https://.../login/guest">} と書かれるだけで
     * 訪問者が意図せずログイン状態にされてしまいます。
     * POST なら CSRF トークンが要るので、この画面のボタンからしか実行できません。
     */
    @PostMapping("/login/guest")
    @Transactional
    public String loginAsGuest(HttpServletRequest request, HttpServletResponse response) {
        StaffUser guest = staffUserRepository.findByUsername(GUEST_USERNAME)
                .orElseGet(this::createGuest);

        // パスワードの照合はしない。「ボタンを押した人＝ゲスト」と決めているため。
        // 通したい権限（STAFF）だけを持たせた認証情報をその場で組み立てる。
        //
        // ROLE_GUEST を「追加で」持たせるのは、見学者を実スタッフと区別するため。
        // STAFF の画面はそのまま全部見られるが、SecurityConfig はこの印を見て
        // ホール（会計）の書き込みと注文キャンセルだけを見学者に閉じている。
        // 公開文言（「表示のみ・厨房ボードで状態を進める操作だけ」）と
        // 実際に押せる操作を一致させるための切り分け（2026-08-22）。
        StaffUserDetails principal = new StaffUserDetails(guest);
        List<GrantedAuthority> authorities = new ArrayList<>(principal.getAuthorities());
        authorities.add(new SimpleGrantedAuthority("ROLE_GUEST"));
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, authorities);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        log.info("ゲストとしてログインしました（デモ用）");
        return "redirect:/kitchen";
    }

    /**
     * ゲストのアカウントが無ければ作る。
     *
     * <p>パスワードは<b>ログインに使いません</b>が、DB の列が NOT NULL なので
     * 誰にも当てられない値を入れておきます。
     * ここで推測できる値を入れると、通常のログイン画面から
     * ゲストとして入られてしまいます（それ自体は STAFF 権限なので致命的ではありませんが、
     * 入口は 1 つに絞っておくほうが後から考えなくて済みます）。
     */
    private StaffUser createGuest() {
        StaffUser guest = new StaffUser(
                GUEST_USERNAME,
                "{noop}" + java.util.UUID.randomUUID(),
                guestDisplayName,
                StaffRole.STAFF);
        log.info("ゲスト用アカウントを作成しました: {}", GUEST_USERNAME);
        return staffUserRepository.save(guest);
    }
}
