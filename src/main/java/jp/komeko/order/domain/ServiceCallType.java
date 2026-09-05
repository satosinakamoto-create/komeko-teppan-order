package jp.komeko.order.domain;

/**
 * お客さまからの呼び出しの種類。
 *
 * <p><b>ここには「持ってくる物」を入れないこと。</b>
 * お水・おしぼり・取り皿・灰皿・塩コショウ・領収書は、
 * ¥0 の商品として<b>注文の仕組みに乗せて</b>厨房ボードへ流しています
 * （設計 暗03 サービス／2026-09-05 に店主と決めた）。
 * 物には「誰が持っていったか」「出したか」という進行があり、
 * それはすでに注文の状態遷移が持っているからです。
 *
 * <p>こちらに入るのは<b>物が伴わない呼びかけ</b>だけです。
 * 進行は「呼ばれた → 対応した」の 2 つしかなく、
 * 金額も個数も無いので、注文に混ぜると売上と客単価の意味が濁ります。
 */
public enum ServiceCallType {

    /** スタッフを呼ぶ。用件は口で伝わるので、こちらでは持たない。 */
    STAFF("スタッフを呼ぶ", "お呼びです"),

    /** お会計をお願いする。ホールが伝票を持って向かう合図。 */
    CHECKOUT("お会計をお願いする", "お会計をご希望です");

    /** お客さまの画面に出す言葉（ボタンやタイルの文字）。 */
    private final String customerLabel;

    /** ホール画面に出す言葉。卓名に続けて読める形にしてある。 */
    private final String staffLabel;

    ServiceCallType(String customerLabel, String staffLabel) {
        this.customerLabel = customerLabel;
        this.staffLabel = staffLabel;
    }

    public String getCustomerLabel() {
        return customerLabel;
    }

    public String getStaffLabel() {
        return staffLabel;
    }
}
