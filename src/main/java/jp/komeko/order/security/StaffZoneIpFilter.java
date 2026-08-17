package jp.komeko.order.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.komeko.order.config.SecurityAccessProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * スタッフ用 URL への「接続元 IP 制限」フィルタ。
 *
 * <p><b>どこで効くか</b><br>
 * ログイン処理よりも前に置かれるため、許可外の端末は
 * <b>ログイン画面を表示することすらできません</b>（403 を返す）。
 * パスワードの正しさを確かめる以前に、話しかける資格から確認する、という発想です。
 *
 * <p><b>フィルタとは</b><br>
 * サーブレットフィルタは「コントローラに届く前の関所」です。
 * リクエストは フィルタ列 → コントローラ の順で流れ、
 * 関所で {@code sendError} すれば以降には一切届きません。
 * Spring Security 自体も実はフィルタの集合でできています。
 *
 * <p><b>正直な限界も知っておく</b><br>
 * IP アドレスは、同じ LAN にいる本気の攻撃者には偽装され得ます（ARP スプーフィング等）。
 * つまりこれは暗号のような絶対的な壁ではなく「錠を二重にする」対策です。
 * それでも、お客さまの端末からスタッフ画面が見えなくなる効果は大きく、
 * パスワードと独立して破らなければならない関門が 1 つ増えます。
 */
public class StaffZoneIpFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(StaffZoneIpFilter.class);

    /**
     * 制限対象の URL（スタッフゾーン）。
     * /login を含めるのがポイント：ログイン試行そのものを許可端末に限定する。
     * お客さま用の URL（/ /items /cart /bill /t など）は対象外。
     */
    private static final String[] STAFF_PREFIXES = {
            "/kitchen", "/hall", "/admin", "/api/stream", "/api/kitchen", "/h2-console"
    };

    private final boolean enabled;
    private final List<IpAddressMatcher> allowed = new ArrayList<>();

    public StaffZoneIpFilter(SecurityAccessProperties properties) {
        List<String> configured = properties.effectiveAllowedIps();
        this.enabled = !configured.isEmpty();
        if (!enabled) {
            log.info("スタッフ画面の接続元制限: 無効（app.staff-access.allowed-ips が空）");
            return;
        }

        // サーバ PC 自身は常に許可。設定を書き間違えても、
        // 本体の前に座れば必ず直せる「非常口」を残しておく。
        allowed.add(new IpAddressMatcher("127.0.0.1"));
        allowed.add(new IpAddressMatcher("::1"));

        for (String entry : configured) {
            try {
                allowed.add(new IpAddressMatcher(entry.trim()));
            } catch (Exception e) {
                // 起動時に大きな音で失敗させる。黙って無視すると
                // 「制限しているつもりでザル」という最悪の状態になるため。
                throw new IllegalStateException(
                        "app.staff-access.allowed-ips の値が不正です: " + entry, e);
            }
        }
        log.info("スタッフ画面の接続元制限: 有効（許可 {} 件 + localhost）", configured.size());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (enabled && isStaffZone(request.getRequestURI()) && !isAllowed(request)) {
            // 何をどこから拒否したかは必ずログに残す（不審なアクセスの痕跡になる）
            log.warn("許可外の接続元からスタッフ画面へのアクセスを拒否しました: {} → {}",
                    request.getRemoteAddr(), request.getRequestURI());
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isStaffZone(String uri) {
        if (uri.equals("/login")) {
            return true;
        }
        for (String prefix : STAFF_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowed(HttpServletRequest request) {
        for (IpAddressMatcher matcher : allowed) {
            if (matcher.matches(request)) {
                return true;
            }
        }
        return false;
    }
}
