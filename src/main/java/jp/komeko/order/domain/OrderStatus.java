package jp.komeko.order.domain;

import java.util.List;

/**
 * 注文の状態。
 *
 * <p>テイクアウト（番号呼び出し）の業務フローに合わせて 5 段階にしています。
 *
 * <pre>
 *   RECEIVED  受付        お客さんが注文を確定した直後。まだ鉄板に乗っていない。
 *      │
 *      ├─────────────→ CANCELED  キャンセル（お客都合・品切れなど）
 *      ↓
 *   COOKING   調理中      鉄板で焼き始めた。サイネージに「調理中」として出る。
 *      │
 *      ├─────────────→ CANCELED
 *      ↓
 *   READY     お渡し可    焼き上がり。番号を呼び出す。サイネージに大きく出る。
 *      │
 *      ↓
 *   COMPLETED 受渡済      お客さんに渡して会計も済んだ。伝票クローズ。
 * </pre>
 *
 * <p>enum（列挙型）にしておくと、String で "cooking" と書き間違えるミスが
 * コンパイル時点で防げます。DB にも文字列で保存します（{@code @Enumerated(EnumType.STRING)}）。
 */
public enum OrderStatus {

    RECEIVED("受付", "受付済み", "#f59e0b"),
    COOKING("調理中", "焼いています", "#ef4444"),
    READY("お渡し可", "お呼び出し中", "#10b981"),
    COMPLETED("受渡済", "お渡し済み", "#64748b"),
    CANCELED("キャンセル", "キャンセル済み", "#94a3b8");

    /** 店側の画面に出す短いラベル */
    private final String staffLabel;
    /** お客さんの画面に出すやわらかいラベル */
    private final String customerLabel;
    /** 画面上の色（CSS でそのまま使える） */
    private final String color;

    OrderStatus(String staffLabel, String customerLabel, String color) {
        this.staffLabel = staffLabel;
        this.customerLabel = customerLabel;
        this.color = color;
    }

    public String getStaffLabel() {
        return staffLabel;
    }

    public String getCustomerLabel() {
        return customerLabel;
    }

    public String getColor() {
        return color;
    }

    /** まだ厨房で作業が残っている状態か（待ち組数の計算に使う）。 */
    public boolean isActive() {
        return this == RECEIVED || this == COOKING;
    }

    /** 伝票としてクローズ済みか。 */
    public boolean isClosed() {
        return this == COMPLETED || this == CANCELED;
    }

    /**
     * この状態から次に進める状態の一覧。
     * 画面のボタン表示と、サーバ側の不正な状態遷移チェックの両方でこれを使います。
     * （画面側だけでチェックすると、URL を直接叩かれたときに素通りしてしまうため）
     */
    public List<OrderStatus> allowedNext() {
        return switch (this) {
            case RECEIVED -> List.of(COOKING, READY, CANCELED);
            case COOKING -> List.of(READY, CANCELED);
            case READY -> List.of(COMPLETED, COOKING, CANCELED);
            case COMPLETED, CANCELED -> List.of();
        };
    }

    /** {@code next} へ遷移してよいか。 */
    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext().contains(next);
    }
}
