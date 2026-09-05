package jp.komeko.order.config;

import jp.komeko.order.security.StaffUserDetailsService;
import jp.komeko.order.security.StaffZoneIpFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
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

    /**
     * 開発用の確認ページ（{@code /dev/**}）を開けるか。
     *
     * <p><b>コントローラと同じスイッチを見ていること。</b>
     * {@code DevPhonePreviewController} も {@code app.dev-tools} で作られます。
     * プロファイル（dev かどうか）で切ると、片方だけが有効な状態を作れてしまい、
     * 「画面はあるのにログインを求められる」「認可は開いているのに 404」の
     * どちらも起こせます。1 つの値で両方を決めます。
     */
    private final boolean devToolsEnabled;

    public SecurityConfig(Environment environment,
                          @Value("${app.guest-login:false}") boolean guestLoginEnabled,
                          @Value("${app.dev-tools:false}") boolean devToolsEnabled) {
        this.environment = environment;
        this.guestLoginEnabled = guestLoginEnabled;
        this.devToolsEnabled = devToolsEnabled;
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
                            // 注文した直後の「承りました」（暗07）。
                            // /ordered/{publicToken} で、いま通った注文だけを出す。
                            // トークンは UUID なので、番号をずらして他の卓の注文を
                            // のぞくことはできない（キャンセルの口と同じ考え方）
                            "/ordered/**",
                            // サービスの画面（暗03）。卓に着いているかは
                            // ServiceController が TableContext で見る。
                            // スタッフゾーンではないので接続元 IP の制限もかけない
                            "/service", "/service/**",
                            "/api/public/**").permitAll()

                    // ── 公開デモの QR 用の、変わらない入口 ──
                    //   /t/{トークン} は卓を作り直すたびに変わるので、
                    //   ポートフォリオに貼った QR は翌朝には死ぬ。
                    //   /demo は中で空いている卓へ橋渡しするだけの固定 URL。
                    //   コントローラ自体が app.guest-login=true のときしか作られないので、
                    //   実店舗ではここを許可していても 404 になる（DemoEntryController 参照）。
                    //   /demo/qr.png はサイトに貼る QR 画像そのもの。
                    //   "/demo" は完全一致なので、こちらを書かないと画像だけ
                    //   ログインを要求されて表示できない。
                    //   /demo/staff は店舗側の見学入口。中身は
                    //   「ゲストで参加する」を自動で押すだけのページなので、
                    //   ログイン前でも開ける必要がある（DemoEntryController 参照）。
                    .requestMatchers("/demo", "/demo/qr.png", "/demo/staff").permitAll()

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

                if (devToolsEnabled) {
                    // お客さまのスマホ画面を実寸で見る確認用ページ（/dev/phone）。
                    //
                    // ★ ここを開けても本番には影響しない。
                    //   DevPhonePreviewController は同じ app.dev-tools で作られるので、
                    //   実店舗ではコントローラ自体が無く、この行も通らない。
                    auth.requestMatchers("/dev/**").permitAll();
                }

                if (guestLoginEnabled) {
                    // ── 公開デモのときだけ、管理画面を「見るだけ」開放する ──
                    //
                    // ゲスト（STAFF 権限）に管理画面が一切見えないと、
                    // 作ってあるものの大半が伝わりません。
                    // かといって書き換えられると、見に来た次の人が壊れた画面を見ることになる。
                    // そこで「見るのは全部許す・書き換えは一切許さない」で切ります。
                    //
                    // ★ この切り分けが成立するのは、管理画面の作りが
                    //   「表示はすべて @GetMapping・変更はすべて @PostMapping」に
                    //   きれいに分かれているからです。
                    //   GET で状態が変わる口が 1 つでもあると、この 1 行が嘘になります。
                    //   AdminMenuItemController / AdminTableController / AdminSettingController /
                    //   AdminStaffController / AdminCategoryController / AdminOptionController を
                    //   実際に確認済み（2026-08-19）。新しい画面を足すときも同じ規律で書くこと。
                    //
                    // ★ 順番が大事。先に書いたルールが勝つので、
                    //   バックアップの除外 → GET 許可 → 「/admin/** は ADMIN のみ」の順に置く。

                    // バックアップ画面だけは GET も開けない。
                    // サーバ上の保存先パスや世代の一覧が出るうえ、
                    // demo プロファイルではバックアップ自体を止めてある（application-demo.yml）ので、
                    // 開けても「無効です」と出るだけで見せる中身がありません。
                    auth.requestMatchers("/admin/backups", "/admin/backups/**")
                            .hasRole("ADMIN");

                    // それ以外の管理画面は GET だけ開ける。
                    // POST は下の /admin/** ルールに落ちて ADMIN 限定のまま。
                    auth.requestMatchers(HttpMethod.GET, "/admin", "/admin/**")
                            .hasAnyRole("STAFF", "ADMIN");

                    // ── 品切れ・残数の変更は見学者に許さない ──
                    //
                    // この画面は /kitchen 配下にあるため、下の
                    // 「/kitchen/** は STAFF 以上」に引っかかって通ってしまいます。
                    // 実際、公開デモで見学者が品切れボタンを押せる状態になっていました。
                    //
                    // 厨房ボードの「焼きはじめ／焼き上がり」は<b>あえて押せるまま</b>にします。
                    // 注文の状態を進めると客側の画面がその場で変わる、が最大の見せ場だからです。
                    // 一方、品切れ・残数は<b>次に見に来た人の画面を壊す</b>操作です。
                    // 全品を品切れにされたら、そのあと誰も注文を試せません。
                    //
                    // 同じ「書き込み」でも、直後に元へ戻るものと、
                    // 後から来る人に残るものは分けて考える必要があります。
                    auth.requestMatchers(HttpMethod.POST, "/kitchen/stock/**")
                            .hasRole("ADMIN");

                    // ── ホール（会計）の書き込みと、注文キャンセルも見学者に許さない ──
                    //
                    // 以前は /hall の POST もゲストに開けていた（「押してもらってこそ」の
                    // 判断で、layout/staff.html にもそう書いていた）。だが公開している
                    // 説明は一貫して「表示のみで、保存や削除はできません。厨房ボードで
                    // 注文の状態を進める操作だけお試しいただけます」であり、
                    // 実際に押せる操作と食い違っていた（2026-08-22 のレビューで指摘）。
                    //
                    // それに、会計で伝票を締める・注文をキャンセルする、はどちらも
                    // 「次に見に来た人の画面を壊す」側の操作でもある。見学用の卓の伝票を
                    // 締められると、次の見学者の /demo が壊れた状態から始まる。
                    // 文言を実装に合わせるのではなく、実装を公開している約束に合わせた。
                    //
                    // ゲストは STAFF に加えて ROLE_GUEST を持つ（GuestLoginController）。
                    // その印がある人だけ、この 2 つを閉じる。実スタッフには影響しない。
                    auth.requestMatchers(HttpMethod.POST, "/hall/**")
                            .access(new WebExpressionAuthorizationManager(
                                    "hasAnyRole('STAFF','ADMIN') and !hasRole('GUEST')"));
                    auth.requestMatchers(HttpMethod.POST, "/kitchen/orders/*/cancel")
                            .access(new WebExpressionAuthorizationManager(
                                    "hasAnyRole('STAFF','ADMIN') and !hasRole('GUEST')"));

                    // ── 仕入れ・在庫の書き込みも見学者に許さない ──
                    //
                    // 見学者に見せたいのは「レシートを撮ると帳簿になる」という流れなので、
                    // 画面（GET）は開けたままにします。
                    // 一方、登録・取り消しは<b>次に見に来た人に残る</b>操作です。
                    // でたらめな仕入れを積まれると、原価率の数字が壊れたまま次の人に見えます。
                    auth.requestMatchers(HttpMethod.POST, "/inventory/**")
                            .access(new WebExpressionAuthorizationManager(
                                    "hasAnyRole('STAFF','ADMIN') and !hasRole('GUEST')"));
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
                    // ── 仕入れ・在庫もスタッフ以上 ──
                    //   買い出しに行くのは店長とは限らないので、STAFF でも登録できるようにする。
                    //   app.inventory.enabled=false のときはコントローラ自体が存在しないため、
                    //   ここを許可していても 404 になる（InventoryPurchaseController 参照）。
                    .requestMatchers("/inventory/**").hasAnyRole("STAFF", "ADMIN")
                    // ── 税理士の画面 ──
                    //   顧問税理士（ACCOUNTANT）と店長（ADMIN）だけ。
                    //   店長にも見せるのは、「税理士に何が見えているか」を
                    //   店主が確認できないと、外部に渡す情報の責任が持てないため。
                    //   スタッフには見せない（帳簿と原価が全部見えるので）。
                    .requestMatchers("/accountant/**").hasAnyRole("ACCOUNTANT", "ADMIN")
                    // ── 上記以外はすべて要ログイン ──
                    .anyRequest().authenticated();
            })
            .formLogin(form -> form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    // ── ログインしたあとの行き先は、役割で分ける ──
                    //
                    // ここは長らく defaultSuccessUrl("/kitchen", true) の一択でした。
                    // ところが税理士（ACCOUNTANT）は /kitchen に入れません
                    // （上の requestMatchers で STAFF と ADMIN だけに絞っているため）。
                    // つまり税理士は、正しいパスワードでログインした直後に
                    // 必ず 403 の画面を見て、自分でアドレス欄に /accountant と
                    // 打ち直さないと仕事を始められませんでした。
                    //
                    // 権限の設定は正しく、行き先だけが間違っている状態だったので、
                    // ここで役割を見て振り分けます。
                    //
                    // 第 2 引数 true と同じ挙動（元のリクエストに戻さず必ずここへ行く）を
                    // 保つため、SavedRequest は見ずに素直にリダイレクトします。
                    .successHandler((request, response, authentication) -> {
                        boolean accountant = authentication.getAuthorities().stream()
                                .anyMatch(a -> "ROLE_ACCOUNTANT".equals(a.getAuthority()));
                        String target = accountant ? "/accountant" : "/kitchen";
                        response.sendRedirect(request.getContextPath() + target);
                    })
                    .failureUrl("/login?error")
                    .permitAll())
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .permitAll())
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

            // ── 見学入口で CSRF が切れたときは、行き止まりにしない ──
            //
            // /demo/staff は開いた瞬間に POST を送るページです。
            // 戻るボタンやブラウザのページ復元で古い HTML が出てくると、
            // 期限切れのトークンで送信され 403 の画面で終わってしまいます。
            // ポートフォリオから来た人にとって、そこが行き止まりになります。
            //
            // トークンが切れただけなら、新しいページを取り直せば済む話です。
            // ?retry=1 を付けて戻し、そちらでは自動送信せずボタンを出します
            // （自動で送り続けると、失敗し続ける場合に往復が止まらなくなる）。
            //
            // ★ 対象を /login/guest の POST だけに絞っています。
            //   ほかの 403 まで拾うと、権限が無いことを権限の問題として
            //   伝えられなくなります。見学者が /admin/settings を保存しようとしたときは、
            //   きちんと 403 のままであるべきです。
            .exceptionHandling(ex -> ex.accessDeniedHandler((request, response, denied) -> {
                // ★ getServletPath() ではなく getRequestURI() で見る。
                //   MockMvc では getServletPath() が空文字になり、条件が常に false になる。
                //   実際それでテストが 403 のまま落ちた。
                //   本番だけ通ってテストで再現できない書き方は、避けたほうがいい。
                String path = request.getRequestURI();
                String context = request.getContextPath();
                if (context != null && !context.isEmpty() && path.startsWith(context)) {
                    path = path.substring(context.length());
                }
                boolean guestLoginPost = "POST".equalsIgnoreCase(request.getMethod())
                        && "/login/guest".equals(path);
                if (guestLoginEnabled && guestLoginPost) {
                    response.sendRedirect(request.getContextPath() + "/demo/staff?retry=1");
                    return;
                }
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }));

        if (devMode) {
            // H2 コンソールは自前のフォームを持っていて CSRF トークンを送れないので除外し、
            // frame 内表示も許可する。dev 限定なので本番の安全性には影響しない。
            http.csrf(csrf -> csrf.ignoringRequestMatchers(PathRequest.toH2Console()));
            http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        }

        return http.build();
    }
}
