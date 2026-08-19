package jp.komeko.order.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * ログイン画面を表示するだけのコントローラ。
 *
 * <p><b>なぜ「1 行の近道」を使わないのか</b><br>
 * Spring MVC には {@code registry.addViewController("/login").setViewName("login")} という
 * 便利な書き方があり、以前はそれを使っていました。
 * ですがこの近道で登録した画面には、
 * <b>{@link GlobalModelAttributes}（{@code @ControllerAdvice}）で用意した共通の値が渡りません</b>。
 * あの仕組みは「{@code @Controller} のメソッド」を対象にしているためです。
 *
 * <p>その結果、ログイン画面で {@code ${guestLogin}} が常に空になり、
 * 設定を true にしても「ゲストで参加する」のボタンが出ませんでした。
 * ボタンを押す経路（POST /login/guest）は動いているのに画面に出ない、
 * という原因の分かりにくい詰まり方をします。
 *
 * <p>共通の値を使う画面は、近道を使わずこうして受けること。
 *
 * <p><b>Spring Security との関係</b><br>
 * {@code .loginPage("/login")} は「ログイン画面の URL はここです」と伝えるだけで、
 * <b>その URL を表示するコントローラまでは用意してくれません</b>。
 * 何も無いと GET /login が 404 になり、
 * 未ログイン時のリダイレクト先も /login なので、無限ループに見える現象が起きます。
 */
@Controller
public class LoginPageController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
