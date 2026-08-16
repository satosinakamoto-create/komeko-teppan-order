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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
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
