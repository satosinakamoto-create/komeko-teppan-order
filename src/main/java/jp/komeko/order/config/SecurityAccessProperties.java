package jp.komeko.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * スタッフ用画面への「接続元制限」の設定（application.yml の {@code app.staff-access:}）。
 *
 * <p><b>何のための設定か</b><br>
 * お客さまのスマホは注文のために店内ネットワークへ入ります。
 * つまり「同じネットワークにいること」はスタッフの証明になりません。
 * そこで、スタッフ用の URL（/login /kitchen /hall /admin）を
 * <b>ここに書いた IP アドレスからしか開けない</b>ようにします。
 *
 * <p>パスワード（知っているかの確認）とは独立した第二の錠なので、
 * 万一パスワードが漏れても、許可外の端末からはログイン画面すら表示されません。
 *
 * <p><b>「有線だけに限定したい」をこれで実現する</b><br>
 * アプリからは接続が有線か無線かは見えず、分かるのは相手の IP だけです。
 * そこでルーターの「DHCP 固定割当」でスタッフ端末に IP を予約し、
 * その IP をここに並べることで、実質的に「この端末だけ」を表現します。
 *
 * @param allowedIps 許可する接続元。IP 単体（192.168.1.21）と
 *                   CIDR 範囲（192.168.1.0/28）が使える。
 *                   <b>空のままなら制限なし</b>（従来どおり）。
 *                   サーバ PC 自身（localhost）は設定に関係なく常に許可される
 *                   （設定ミスで誰も入れなくなる事故を防ぐため）
 */
@ConfigurationProperties(prefix = "app.staff-access")
public record SecurityAccessProperties(List<String> allowedIps) {

    /** null や空白entryを取り除いた実効リスト。 */
    public List<String> effectiveAllowedIps() {
        if (allowedIps == null) {
            return List.of();
        }
        return allowedIps.stream()
                .filter(s -> s != null && !s.isBlank())
                .toList();
    }
}
