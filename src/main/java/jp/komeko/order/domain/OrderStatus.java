package jp.komeko.order.domain;

import java.util.List;
import java.util.Optional;

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
     *
     * <p><b>並び順にも意味があります。</b>
     * {@link #CANCELED} を除いた<b>先頭が「その状態での標準の次の一手」</b>です
     * （受付なら調理中、調理中なら焼き上がり、提供待ちなら提供済み）。
     * 2 番目以降は、工程を飛ばす・一段戻すといった<b>例外的な操作</b>です。
     * 並べ替えるときは {@link #primaryNext()} の意味ごと変わるので注意してください。
     */
    public List<OrderStatus> allowedNext() {
        return switch (this) {
            case RECEIVED -> List.of(COOKING, READY, CANCELED);
            case COOKING -> List.of(READY, CANCELED);
            case READY -> List.of(COMPLETED, COOKING, CANCELED);
            case COMPLETED, CANCELED -> List.of();
        };
    }

    /**
     * この状態での<b>標準の次の一手</b>（＝ふつうに進めるならこれ）。
     *
     * <pre>
     *   RECEIVED → COOKING    受付の伝票は、まず鉄板に乗せる
     *   COOKING  → READY      焼いている伝票は、焼き上がる
     *   READY    → COMPLETED  焼き上がった料理は、卓へ運んで提供済みになる
     *   COMPLETED / CANCELED  もう進む先が無い（空を返す）
     * </pre>
     *
     * <p><b>なぜ画面の条件式ではなく enum に置くのか</b><br>
     * 「受付の次はふつう調理中」は<b>お店の仕事の順序そのもの</b>であって、
     * ボタンの色の都合ではありません。{@link #allowedNext()} が持つ並び順に
     * すでにその知識が入っていましたが、<b>暗黙の約束</b>のままでした。
     * 画面側に {@code allowedNext()[0]} と書くと、その暗黙知が画面に漏れ、
     * <ul>
     *   <li>同じ判断を使う画面が増えるたびに同じ式をコピーすることになる</li>
     *   <li>並び順を変えた人が「先頭に意味がある」と気づけない</li>
     *   <li>テンプレートの三項演算子はテストで固定しづらい</li>
     * </ul>
     * という三つの問題が出ます。名前を付けて enum に置けば、
     * 素の JUnit で全状態を網羅して固定できます。
     *
     * <p>厨房ボードの実例：受付レーンには「焼きはじめ（→調理中）」と
     * 「焼き上がり（→お渡し可）」の 2 つのボタンが出ます。後者はドリンクだけの注文で
     * 焼く工程を飛ばすための操作なので、目立たせるべきは前者です。
     * <b>遷移先の名前ではなく「そのレーンでの標準の一手かどうか」で見た目を決める</b>ために
     * このメソッドを使います。
     *
     * @return 標準の次の状態。もう進めない状態なら {@link Optional#empty()}
     */
    public Optional<OrderStatus> primaryNext() {
        // キャンセルはどの状態からでも選べる「横道」なので、標準の一手には決してならない。
        // allowedNext() の末尾に必ず置いてあるが、順序に頼らず明示的に除外しておく。
        return allowedNext().stream()
                .filter(status -> status != CANCELED)
                .findFirst();
    }

    /**
     * {@code next} は、この状態での標準の次の一手か。
     *
     * <p>{@link #primaryNext()} が空（もう進めない状態）のときは常に false です。
     * 画面から呼びやすいよう、{@link Optional} を開かずに真偽値で答えます。
     */
    public boolean isPrimaryNext(OrderStatus next) {
        return primaryNext().filter(primary -> primary == next).isPresent();
    }

    /** {@code next} へ遷移してよいか。 */
    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext().contains(next);
    }
}
