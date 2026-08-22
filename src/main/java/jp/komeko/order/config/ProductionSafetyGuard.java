package jp.komeko.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 実店舗（prod）を、デモ用の設定ミスから守る安全装置。
 *
 * <p>公開デモの仕掛けはどれも「環境変数 1 つ」で有効になる。
 * それは便利さと引き換えに、<b>実店舗の環境にその変数が紛れ込むだけで
 * 事故になる</b>ということでもある。
 *
 * <ul>
 *   <li>{@code APP_GUEST_LOGIN=true} が prod に紛れると、
 *       誰でもボタン 1 つで STAFF 権限に入れて、管理画面の閲覧まで開く</li>
 *   <li>{@code SPRING_PROFILES_ACTIVE=demo,prod} のような同時指定だと、
 *       DemoDataSeeder が実店舗の DB に架空の卓・伝票・注文を書き込み得る</li>
 * </ul>
 *
 * <p>どちらも「気づいたら直す」で済ませられない（開店中に気づくのは店頭）。
 * そこで<b>起動そのものを失敗させる</b>。設定を直すまでアプリは立ち上がらない。
 *
 * <p>Render のデモ環境は prod プロファイルを使わないので、この装置には触れない。
 */
@Component
public class ProductionSafetyGuard {

    public ProductionSafetyGuard(Environment environment,
                                 @Value("${app.guest-login:false}") boolean guestLoginEnabled) {
        check(Arrays.asList(environment.getActiveProfiles()), guestLoginEnabled);
    }

    /** 検証本体（テストから直接呼べるように分けてある）。 */
    static void check(List<String> activeProfiles, boolean guestLoginEnabled) {
        if (!activeProfiles.contains("prod")) {
            return;
        }
        if (guestLoginEnabled) {
            throw new IllegalStateException("""
                    実店舗（prod）でゲストログインが有効になっています。 \
                    APP_GUEST_LOGIN は公開デモ専用の設定です。 \
                    このまま起動すると誰でもスタッフ画面に入れるため、起動を中止しました。 \
                    環境変数から APP_GUEST_LOGIN を外して再起動してください。""");
        }
        if (activeProfiles.contains("demo") || activeProfiles.contains("dev")) {
            throw new IllegalStateException("""
                    prod と %s のプロファイルが同時に有効になっています。 \
                    デモ用のシーダーが実店舗のデータベースに架空のデータを書き込む恐れがあるため、 \
                    起動を中止しました。SPRING_PROFILES_ACTIVE は prod だけにしてください。"""
                    .formatted(activeProfiles.contains("demo") ? "demo" : "dev"));
        }
    }
}
