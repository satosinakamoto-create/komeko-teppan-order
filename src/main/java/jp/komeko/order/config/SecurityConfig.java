package jp.komeko.order.config;

import jp.komeko.order.security.StaffUserDetailsService;
import jp.komeko.order.security.StaffZoneIpFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * ログインとアクセス制御の設定。
 *
 * <p><b>このアプリの「誰が何を見られるか」</b>
 * <table border="1">
 *   <caption>URL ごとの権限</caption>
 *   <tr><th>URL</th><th>誰が見られるか</th></tr>
 *   <tr><td>/t/**</td><td>誰でも（卓の QR の飛び先）</td></tr>
 *   <tr><td>/ , /items/** , /cart/** , /bill/**</td><td>誰でも（お客さん）</td></tr>
 *   <tr><td>/kitchen/** , /hall/**</td><td>STAFF 以上</td></tr>
 *   <tr><td>/admin/**</td><td>ADMIN のみ</td></tr>
 * </table>
 *
 * <p>お客さん側にログインはありません。かわりに
 * 「推測できないトークンを知っていること」を本人確認の材料にしています
 * （ケイパビリティ URL）。連番の ID を URL に出すと、
 * 番号を変えるだけで他の卓の伝票が覗けてしまいます。
 *
 * <p><b>CSRF について</b><br>
 * Spring Security は既定で CSRF 対策が有効です。
 * Thymeleaf の {@code <form th:action=...>} を使えば
 * 隠しトークンが自動で埋め込まれるので、特別な作業は要りません。
 * 逆に、フォームを素の {@code <form action=...>} で書くと 403 になります。
 * 「POST したら 403」で悩んだらここを疑ってください。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // サービスのメソッドに @PreAuthorize を書けるようにする
public class SecurityConfig {

    private final Environment environment;

    /**
     * 公開デモかどうか（{@code APP_GUEST_LOGIN=true}）。
     * true のときだけ、一部の管理画面を「見るだけ」開放する。
     */
    private final boolean guestLoginEnabled;

    public SecurityConfig(Environment environment,
                          @Value("${app.guest-login:false}") boolean guestLoginEnabled) {
        this.environment = environment;
        this.guestLoginEnabled = guestLoginEnabled;
    }

    /**
     * パスワードのハッシュ化方式。
     *
     * <p>BCrypt は「ソルト（毎回違う値）を混ぜてハッシュ化する」ため、
     * 同じパスワードでも保存されるハッシュは毎回変わります。
     * また計算にわざと時間をかける設計で、総当たり攻撃に強くなっています。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(StaffUserDetailsService userDetailsService,
                                                            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // ユーザーが存在しない場合もパスワード照合を実行して、応答時間から
        // ユーザーの存在有無が推測されないようにする
        provider.setHideUserNotFoundExceptions(true);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityAccessProperties accessProperties) throws Exception {
        boolean devMode = environment.matchesProfiles("dev");

        // ── 接続元 IP によるスタッフゾーンの制限（第二の錠） ──
        // ログイン処理よりも前（UsernamePasswordAuthenticationFilter の手前）に置くことで、
        // 許可外の端末には /login の表示すらさせない。
        // app.staff-access.allowed-ips が空なら何もしない（従来どおり）。
        http.addFilterBefore(new StaffZoneIpFilter(accessProperties),
                UsernamePasswordAuthenticationFilter.class);

        http
            .authorizeHttpRequests(auth -> {
                auth
                    // ── 静的ファイル・エラーページ ──
                    .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico",
                            "/uploads/**", "/error").permitAll()

                    // ── お客さん向け（ログイン不要） ──
                    //   /t/{トークン} が卓の QR の飛び先。
                    //   ログインの代わりに「推測できないトークンを知っていること」を
                    //   本人確認の材料にしている（ケイパビリティ URL）。
                    .requestMatchers("/", "/menu", "/items/**", "/cart/**",
                            "/checkout", "/t/**", "/bill/**", "/o/**",
                            "/api/public/**").permitAll()

                    // ── ログイン画面 ──
                    //   /login/guest はポートフォリオのデモ用。
                    //   通ってもスタッフ権限しか付かない（/admin/** には入れない）。
                    //   そもそも app.guest-login=true でないとコントローラが存在しないので、
                    //   実店舗ではこのパスは 404 になる。
                    .requestMatchers("/login", "/login/guest").permitAll()

                    // ── 生存確認（スリープ防止の ping 用） ──
                    //   無料ホスティングは無通信が続くとサーバを眠らせるので、
                    //   外部から定期的にここを叩いて起こしておく。
                    //   DB も画面も触らないため、叩かれてもほぼ無負荷。
                    .requestMatchers("/ping").permitAll();

                if (devMode) {
                    // H2 コンソールは dev プロファイルのときだけ開放する。
                    // PathRequest.toH2Console() は application.yml で設定した
                    // コンソールのパスを自動で拾ってくれる（パスを二重管理しなくて済む）。
                    auth.requestMatchers(PathRequest.toH2Console()).permitAll();
                }

                if (guestLoginEnabled) {
                    // ── 公開デモのときだけ、一部の管理画面を「見るだけ」開放する ──
                    //
                    // ゲスト（STAFF 権限）で入った方に、卓と QR コードが見えないと
                    // 「どうやって注文が始まるのか」が分からず、システムの全体像が伝わりません。
                    // 売上と注文履歴も、データが架空のデモでは見せる価値のほうが大きい。
                    //
                    // ★ GET だけを開ける。POST は下の /admin/** ルールに落ちて ADMIN 限定のまま。
                    //   見るのは許すが、書き換えは許さない、という切り分けです。
                    //   （順番が大事。先に書いたルールが勝つので、この GET 許可は
                    //     「/admin/** は ADMIN のみ」より前に置く必要がある）
                    //
                    // 価格・店舗設定・スタッフ・バックアップは開けません。
                    // 壊されるとデモが成立しなくなるか、他人のパスワードに触れてしまうためです。
                    //
                    // 卓の管理画面（/admin/tables）も開けていません。
                    // あの画面は入力欄と保存ボタンで組み上がっていて、
                    // 「見るだけ」にすると中身が空の行が並ぶだけになります。
                    // 卓の名前・QR・URL は QR コードの画面にすべて載っているので、
                    // 見せたい情報はそちらで足ります。
                    auth.requestMatchers(HttpMethod.GET,
                                    "/admin",
                                    "/admin/qr", "/admin/qr/**",
                                    "/admin/sales", "/admin/sales/**",
                                    "/admin/orders", "/admin/orders/**")
                            .hasAnyRole("STAFF", "ADMIN");
                }

                auth
                    // ── 管理画面は ADMIN のみ ──
                    //   上のデモ用 GET 許可に当てはまらなかったものは、すべてここで止まる。
                    //   つまり POST（追加・更新・削除）は必ず ADMIN が必要。
                    .requestMatchers("/admin/**").hasRole("ADMIN")
                    // ── 厨房・ホール（会計）はスタッフ以上 ──
                    .requestMatchers("/kitchen/**", "/hall/**",
                            "/api/kitchen/**", "/api/stream/**")
                        .hasAnyRole("STAFF", "ADMIN")
                    // ── 上記以外はすべて要ログイン ──
                    .anyRequest().authenticated();
            })
            .formLogin(form -> form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .defaultSuccessUrl("/kitchen", true)
                    .failureUrl("/login?error")
                    .permitAll())
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .permitAll())
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));

        if (devMode) {
            // H2 コンソールは自前のフォームを持っていて CSRF トークンを送れないので除外し、
            // frame 内表示も許可する。dev 限定なので本番の安全性には影響しない。
            http.csrf(csrf -> csrf.ignoringRequestMatchers(PathRequest.toH2Console()));
            http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        }

        return http.build();
    }
}
