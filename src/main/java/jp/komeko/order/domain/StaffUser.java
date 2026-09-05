package jp.komeko.order.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * スタッフ（厨房・管理画面のログインユーザー）。
 *
 * <p><b>パスワードは絶対に平文で保存しない</b><br>
 * {@link #passwordHash} には BCrypt でハッシュ化した文字列だけを入れます。
 * BCrypt は「同じパスワードでも毎回違うハッシュになる」「計算にわざと時間がかかる」
 * という性質があり、DB が漏れても元のパスワードを割り出しにくくなります。
 * ハッシュ化は {@code PasswordEncoder}（SecurityConfig で定義）が行います。
 */
@Entity
@Table(name = "staff_user")
public class StaffUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "ユーザー名を入力してください")
    @Size(max = 40)
    @Column(nullable = false, unique = true, length = 40)
    private String username;

    /** BCrypt ハッシュ（$2a$... で始まる 60 文字程度の文字列）。 */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    @NotBlank(message = "表示名を入力してください")
    @Size(max = 40)
    @Column(nullable = false, length = 40)
    private String displayName;

    /*
     * ★ columnDefinition に varchar を書くこと（2026-09-05 に事故）。
     *
     *   Hibernate 6 は H2 に対して、@Enumerated(STRING) の列を varchar ではなく
     *   ネイティブの ENUM 型で作る。つまり ENUM('ADMIN', 'STAFF') のように
     *   「そのとき存在した値の一覧」が DDL に焼き込まれる。
     *
     *   ddl-auto: update は「無い列を足す」ことしかしないので、
     *   あとから enum に定数を足しても既存 DB の列型は広がらない。
     *   V5 で StaffRole に ACCOUNTANT を足したが、8/30 以前から使っている
     *   dev の data\komeko.mv.db は ENUM('ADMIN','STAFF') のままで、
     *   税理士アカウントを作ろうとすると H2 が
     *     Value not permitted for column "('ADMIN', 'STAFF')": "ACCOUNTANT"
     *   を投げ、500 になる。
     *
     *   varchar を明示しておけば、値を足しても列は何も変わらない。
     *   テストでは絶対に再現しない（毎回まっさらな DB を作るため）。
     *   すでに ENUM 型で作られてしまった dev DB は、data\ を消して作り直す。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private StaffRole role = StaffRole.STAFF;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected StaffUser() {
    }

    public StaffUser(String username, String passwordHash, String displayName, StaffRole role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public StaffRole getRole() {
        return role;
    }

    public void setRole(StaffRole role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
