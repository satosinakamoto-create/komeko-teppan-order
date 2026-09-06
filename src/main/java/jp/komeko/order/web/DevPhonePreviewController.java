package jp.komeko.order.web;

import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.repository.DiningTableRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * お客さまのスマホ画面を、PC で<b>そのままの大きさ</b>で見るための確認用ページ。
 *
 * <h2>なぜ作ったか</h2>
 *
 * <p>PC のブラウザでお客さま側を開くと、窓の幅いっぱいに広がって
 * <b>実機とは違う形</b>になります。設計は 390px で描かれているので、
 * それより広い画面で見たものは「お客さまが見るもの」ではありません。
 *
 * <p>Chrome の端末モード（F12 のあと Ctrl+Shift+M）でも同じことができますが、
 * <b>F12 だけでは切り替わりません</b>。毎回 2 手かかるうえ、
 * 端末の選び直しや倍率の設定でも見え方が変わります。
 * 「実機と同じ 390×844 で見る」という 1 つのことだけをするページを用意しました。
 *
 * <h2>本番には出ません</h2>
 *
 * <p>{@code app.dev-tools=true} のときだけ Bean が作られます。
 * 実店舗の設定（{@code application-prod.yml}）では書いていないので、
 * このコントローラ自体が存在せず、URL は 404 になります。
 *
 * <p>プロファイルではなく設定値で切っているのは、
 * <b>テストから有効にできるようにする</b>ためです。
 * {@code @Profile("dev")} にすると、test プロファイルで走るテストからは
 * 一度も触れられず、壊れても誰も気づけません。
 *
 * <h2>中身は本物です</h2>
 *
 * <p>iframe に本物の URL を読ませているだけで、写しや作り置きではありません。
 * 同じブラウザのセッションを共有するので、卓に着いた状態もそのまま引き継ぎます
 * （QR を読んでいなければ、枠の中にも「QR をお読みください」が出ます）。
 */
@Controller
@ConditionalOnProperty(name = "app.dev-tools", havingValue = "true")
public class DevPhonePreviewController {

    /**
     * 並べる端末。CSS ピクセルの実寸です。
     *
     * <p>iPhone 16 Pro Max の 440 を上限に入れてあるのは、
     * {@code .theme-night} の {@code --page-max} を 440 にした判断
     * （いちばん広いスマホで左右に帯を出さない）を、目で確かめられるようにするため。
     */
    public record Device(String label, int width, int height) {
    }

    private static final List<Device> DEVICES = List.of(
            new Device("iPhone 14 / 15（390×844）", 390, 844),
            new Device("Android 標準（360×800）", 360, 800),
            new Device("iPhone 15 Pro Max（430×932）", 430, 932),
            new Device("iPhone 16 Pro Max（440×956）", 440, 956));

    /**
     * 枠の中に出せる行き先。
     *
     * <p><b>{@code /staff/order} も入れてあります（2026-09-06）。</b>
     * 店舗版は「店員のスマホで使う画面」なので、実寸で確かめられないと
     * 作ったものが現場で使えるか分かりません。
     *
     * <p>ただし {@code /staff/**} はログインが要ります。
     * ログインしていないと枠の中にログイン画面が出るので、
     * 先に別のタブでログインしてから開いてください
     * （画面にもその案内を出しています）。
     */
    private static final List<String> PATHS = List.of(
            "/menu", "/cart", "/bill", "/service", "/", "/staff/order");

    private final DiningTableRepository tables;

    public DevPhonePreviewController(DiningTableRepository tables) {
        this.tables = tables;
    }

    @GetMapping("/dev/phone")
    public String phone(@RequestParam(defaultValue = "/menu") String path,
                        @RequestParam(defaultValue = "390") int w,
                        Model model) {
        Device device = DEVICES.stream()
                .filter(d -> d.width() == w)
                .findFirst()
                .orElse(DEVICES.get(0));

        // 卓に入り直すための入口。
        //
        // ★ なぜここに要るのか
        //   卓の紐づけはメモリ上のセッションにあるので、アプリを再起動すると
        //   全部消えます。開発中は保存のたびに再起動がかかるため、
        //   確かめている最中に「お席の QR をお読みください」に戻ります。
        //   そのたびに卓のトークンを探すのは現実的ではありません。
        //   ここから 1 回押せば、QR を読んだのと同じ状態に戻せます。
        List<DiningTable> seats = tables.findByActiveTrueOrderBySortOrderAscIdAsc();

        // 枠に入れてよいのは、このアプリのお客さま側だけ。
        // 受け取った文字列をそのまま iframe に渡すと、外のサイトを
        // 埋め込む踏み台にできてしまう（開発用でも開けておく理由がない）。
        //
        // 卓の入口（/t/{トークン}）は、実在する卓のものだけ通す。
        // 文字列の形で判定すると、/t/../../ のような細工を通してしまう。
        String target = resolveTarget(path, seats);

        model.addAttribute("devices", DEVICES);
        model.addAttribute("device", device);
        model.addAttribute("paths", PATHS);
        model.addAttribute("path", target);
        model.addAttribute("seats", seats);
        return "dev/phone";
    }

    /** 枠に入れてよい行き先か確かめて返す。だめなら既定のメニューに倒す。 */
    private String resolveTarget(String path, List<DiningTable> seats) {
        if (PATHS.contains(path)) {
            return path;
        }
        for (DiningTable seat : seats) {
            if (("/t/" + seat.getAccessToken()).equals(path)) {
                return path;
            }
        }
        return "/menu";
    }
}
