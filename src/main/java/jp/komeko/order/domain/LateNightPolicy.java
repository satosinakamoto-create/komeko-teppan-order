package jp.komeko.order.domain;

import java.time.LocalDateTime;

/**
 * 「その時刻は深夜料金の対象か」を答える役。
 *
 * <p><b>なぜ boolean ではなく、こういう型を渡すのか</b><br>
 * 以前は {@code recalculate(now, applyLateNight)} のように
 * <b>真偽値を 1 つだけ</b>渡していました。つまり伝票全体に対して
 * 「深夜料金がかかるか、かからないか」を一度決めていたわけです。
 *
 * <p>ところが実際の運用では、<b>深夜料金は注文ごとに決まります</b>。
 * 22:00 に頼んだ品は通常料金、23:30 に頼んだ品は深夜料金、というように
 * 同じ伝票の中で混在します。真偽値 1 つでは、この区別が表現できません。
 *
 * <p>そこで「答えそのもの（true/false）」ではなく
 * <b>「聞けば答えてくれる相手」</b>を渡す形にしました。
 * こうすると {@link TableSession} は注文の件数だけ何度でも問い合わせられます。
 *
 * <pre>
 *   // 店舗設定のルールで判定する（ふつうの会計）
 *   session.recalculate(shopSetting::isLateNight);
 *
 *   // スタッフが深夜料金を免除した会計
 *   session.recalculate(LateNightPolicy.NONE);
 * </pre>
 *
 * <p><b>{@code @FunctionalInterface} とは</b><br>
 * 「抽象メソッドが 1 つだけ」であることをコンパイラに約束させる印です。
 * 1 つだけなら、上のように {@code shopSetting::isLateNight}（メソッド参照）や
 * {@code at -> false}（ラムダ式）を、そのままこの型として渡せます。
 * うっかりメソッドを 2 つ書いてしまうとコンパイルエラーになるので、
 * 「ラムダで渡せる」という前提が壊れないことが保証されます。
 *
 * <p><b>なぜ {@link ShopSetting} を直接渡さないのか</b><br>
 * {@link TableSession} は会計の証跡そのものなので、
 * 「いまの店舗設定」に依存させたくないからです。
 * この型なら、テストで
 * {@code at -> at.getHour() >= 23} のような単純な決めうちを渡せますし、
 * 免除も「深夜料金なし、という方針」として素直に書けます。
 */
@FunctionalInterface
public interface LateNightPolicy {

    /**
     * その時刻に出た注文（または着席）に深夜料金がかかるか。
     *
     * @param at 判定したい時刻。注文なら注文時刻、テーブルチャージなら着席時刻
     */
    boolean appliesAt(LocalDateTime at);

    /**
     * 深夜料金を一切かけない方針。
     *
     * <p>スタッフが会計画面で深夜料金のチェックを外したときに使います。
     * 「免除」を専用のフラグではなくこの形で表すことで、
     * {@link TableSession} 側は「方針に従って計算する」だけで済み、
     * 免除のための場合分けを持たずにすみます。
     */
    LateNightPolicy NONE = at -> false;
}
