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
    ADMIN("管理者");

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
