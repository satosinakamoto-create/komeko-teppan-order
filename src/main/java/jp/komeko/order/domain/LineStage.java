package jp.komeko.order.domain;

import java.util.Set;

/**
 * 品 1 つの段階（2026-09-23 追加）。
 *
 * <pre>
 *   UNCOOKED ──[調理済み]──▶ COOKED ──[提供済み]──▶ SERVED
 *       ▲                      │
 *       └──────[← 戻す]────────┘
 * </pre>
 *
 * <p><b>なぜ {@link OrderStatus} と別に要るのか。</b>
 * {@code OrderStatus} は<b>注文</b>（カートを一度に確定した単位）の状態です。
 * 4 品まとめて頼むと 1 件になるので、「たこ焼だけ焼き上がった」を表せません。
 * 店主の指摘がそのまま理由です——
 * <pre>
 *   「これ一気に注文入ったら一気にその卓の料理しないといけなくなるわけじゃん。
 *     でも調理は一つずつだから、どうやって提供可能のボタン分けたら良いか
 *     って問題に直面してるんだよね」
 * </pre>
 *
 * <p><b>軸は「調理」ひとつです。</b>
 * かつては 受付 → 調理中 → 提供待ち の 3 段でしたが、
 * 焼かない品（瓶ビール・冷やしトマト）が「調理中」を通る意味がなく、
 * また「提供待ち」に置いた品が調理中かどうかを知る方法もありませんでした
 * （店主：「提供待ちで調理中ってのもおかしくない？」）。
 * レーンの名前も段階の名前も、調理の軸だけで揃えてあります。
 *
 * <p><b>取り消し（{@code OrderLine#canceled}）は、この段階とは別に持ちます。</b>
 * 段階は「どこまで進んだか」、取り消しは「請求に乗せるか」で、軸が違います。
 * 混ぜると「提供済みで、かつ取り消し」が表せなくなります——
 * お客さまに出したあとで「これ違います」と言われる場面が、まさにそれです。
 */
public enum LineStage {

    /** まだ焼いていない。厨房ボードの左のレーンに並ぶ。 */
    UNCOOKED("未調理"),

    /** 焼き上がった。まだ卓へ運んでいない。厨房ボードの右のレーンに並ぶ。 */
    COOKED("調理済み"),

    /** 卓へ運んだ。厨房ボードから消える。 */
    SERVED("提供済み");

    private final String label;

    LineStage(String label) {
        this.label = label;
    }

    /** 画面に出す日本語。 */
    public String getLabel() {
        return label;
    }

    /**
     * ここから進める先。
     *
     * <p><b>戻る向きも許しています。</b>押し間違いは必ず起きるからです
     * （店主：「調理済み提供可能ボタンを間違って押したとき、
     * 一個前の状態に戻すボタンが必要なんじゃない？」）。
     * ただし <b>SERVED からは戻しません</b>。運んでしまったあとの訂正は
     * 「取り消し」（{@code OrderLine#cancel}）の仕事で、
     * 段階を巻き戻しても卓の上の料理は戻りません。
     */
    public Set<LineStage> allowedNext() {
        return switch (this) {
            case UNCOOKED -> Set.of(COOKED);
            case COOKED -> Set.of(SERVED, UNCOOKED);
            case SERVED -> Set.of();
        };
    }

    /** そこへ進めてよいか。 */
    public boolean canMoveTo(LineStage next) {
        return allowedNext().contains(next);
    }

    /** 厨房ボードに出すか（提供済みは消える）。 */
    public boolean isOnKitchenBoard() {
        return this != SERVED;
    }
}
