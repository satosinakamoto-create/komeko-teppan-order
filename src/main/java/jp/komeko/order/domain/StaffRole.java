package jp.komeko.order.domain;

/**
 * スタッフの権限。
 *
 * <p>Spring Security では権限名に {@code ROLE_} という接頭辞を付ける慣習があります。
 * {@code hasRole("ADMIN")} と書くと内部的には {@code ROLE_ADMIN} を探しに行くため、
 * DB には {@code ADMIN} だけを保存し、認証時に接頭辞を足す方針にしています。
 */
public enum StaffRole {

    /** 厨房・ホールのスタッフ。注文の進行管理だけができる。 */
    STAFF("スタッフ"),

    /** 店長・管理者。メニュー編集、売上、スタッフ管理までできる。 */
    ADMIN("管理者"),

    /**
     * 顧問税理士。<b>店の記録を見るだけ</b>で、営業には一切触れない。
     *
     * <p>ADMIN の下位ではなく<b>別方向</b>の権限です。
     * 帳簿・証憑・仕入れは見られますが、卓・QR・商品・スタッフといった
     * 店舗運営の画面は見えません。逆に ADMIN からは税理士画面も見えます
     * （店主が「税理士に何が見えているか」を確認できる必要があるため）。
     *
     * <p>書き込めるのは「確認した」という記録だけです。
     * 店の数字を外部の人が直せてしまうと、帳簿の責任の所在が曖昧になります。
     */
    ACCOUNTANT("税理士");

    private final String label;

    StaffRole(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Spring Security に渡す権限名（ROLE_ 付き）。 */
    public String authority() {
        return "ROLE_" + name();
    }
}
