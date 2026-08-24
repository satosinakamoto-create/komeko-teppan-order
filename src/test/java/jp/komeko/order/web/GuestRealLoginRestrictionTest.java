package jp.komeko.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>実際に「ゲストで参加する」を押して得たセッション</b>で、禁止操作が拒否されることを固定するテスト。
 *
 * <p><b>このテストが守っているもの＝ ROLE_GUEST が本当に付いていること</b><br>
 * 見学者の遮断は 2 つの部品でできています。
 * <ol>
 *   <li>{@code GuestLoginController} が、ログイン時に STAFF へ
 *       {@code ROLE_GUEST} を<b>追加で</b>持たせる</li>
 *   <li>{@code SecurityConfig} が、その印を見て
 *       {@code POST /hall/**} と {@code POST /kitchen/orders/*&#47;cancel} を閉じる</li>
 * </ol>
 *
 * <p>ところが遮断のテスト（{@link GuestLoginTest} や
 * {@link KitchenStatusCancelBypassTest}）は
 * {@code @WithMockUser(roles = {"STAFF","GUEST"})} で役割を<b>手で組み立てて</b>います。
 * これは 2 の側しか見ていません。1 の側、つまり
 * {@code authorities.add(new SimpleGrantedAuthority("ROLE_GUEST"))} の 1 行を落としても
 * <b>全件 green のまま</b>で、実際の見学者は
 * {@code /hall/bills/*&#47;close} や {@code /kitchen/orders/*&#47;cancel} を
 * また実行できるようになります。伝票が締まる・注文が消えるという、
 * 次に見に来た人の画面に残る変更です。
 *
 * <p>そこでここでは <b>{@code POST /login/guest} を本当に通してセッションを取り</b>、
 * そのセッションのまま禁止操作を叩きます。手組みの役割は一切使いません。
 *
 * <p><b>403 という数字を見てよい理由</b><br>
 * 未ログインで {@code /hall/**} を叩くと 403 ではなくログイン画面へのリダイレクト（302）です。
 * つまりここで 403 が返るのは「ログインは成立していて、そのうえで拒否された」ときだけ。
 * ログインが失敗しただけの見せかけの green にはなりません。
 * 念のため {@link #guestSessionReallyLogsIn()} で、同じセッションが
 * 厨房ボードを開けること（＝本当にログインできていること）も別に確かめています。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.guest-login=true")   // 見学者が居るのは公開デモの設定だけ
@DisplayName("実ログインしたゲストの遮断（ROLE_GUEST の付与まで含めて）")
class GuestRealLoginRestrictionTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * ログイン画面の「ゲストで参加する」を実際に押して、そのブラウザのセッションを返す。
     *
     * <p>{@code MockHttpSession} を自分で用意して渡すのがポイントです。
     * MockMvc は何も指定しないとリクエストごとに新しいセッションを作るので、
     * ログインで書き込まれた認証情報が次のリクエストへ引き継がれません
     * （{@code GuestLoginController} は {@code HttpSessionSecurityContextRepository} で
     * セッションへ保存しています）。
     */
    private MockHttpSession loginAsGuest() throws Exception {
        MockHttpSession browserSession = new MockHttpSession();
        mockMvc.perform(post("/login/guest").session(browserSession).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/kitchen"));
        return browserSession;
    }

    @Test
    @DisplayName("押すと本当にログインでき、厨房ボードが開ける")
    void guestSessionReallyLogsIn() throws Exception {
        // 下の遮断テストが「ログインできていないから 403」で通ってしまう、
        // という偽の green を防ぐための土台。
        // 既存の guestCanEnterKitchen はリダイレクト先しか見ていないので、
        // セッションを持ち回って次の画面が開けるところまでを別に確かめる
        mockMvc.perform(get("/kitchen").session(loginAsGuest()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} は実ログインしたゲストにも拒否される")
    @ValueSource(strings = {"/hall/bills/1/close", "/hall/bills/1/reopen",
                            "/hall/bills/1/guests", "/hall/tables/1/open",
                            "/hall/bills/1/orders/1/late-night",
                            "/kitchen/orders/1/cancel"})
    @DisplayName("会計まわりの書き込みと注文キャンセルは、実ログインしたゲストでもできない")
    void realGuestCannotWriteHallOrCancelOrders(String path) throws Exception {
        // 存在しない伝票・注文をわざと指しているので、通ってしまった場合は
        // 403 ではなく 3xx（一覧へ戻る）や 404 になる。
        // つまり「認可で止まったか」だけをきれいに見られる
        mockMvc.perform(post(path).session(loginAsGuest()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("実ログインしたゲストでも、厨房ボードの状態を進める操作はできる（見せ場は残す）")
    void realGuestCanStillAdvanceOrderStatus() throws Exception {
        // 遮断を広げすぎて「注文の状態を進めるとお客さまの画面が変わる」という
        // 公開デモ唯一の見せ場まで閉じてしまうと、デモの意味が無くなる。
        // 存在しない注文なので結果は 3xx（ボードへ戻る）で、403 にならないことを見る
        mockMvc.perform(post("/kitchen/orders/999999/status")
                        .session(loginAsGuest())
                        .with(csrf())
                        .param("status", "COOKING"))
                .andExpect(status().is3xxRedirection());
    }
}
