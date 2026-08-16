package jp.komeko.order.security;

import jp.komeko.order.domain.StaffRole;
import jp.komeko.order.domain.StaffUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security にログインユーザーを渡すための入れ物。
 *
 * <p>Security は {@link UserDetails} という形でしかユーザーを扱えないので、
 * 自分の {@link StaffUser} をこのクラスで包んで渡します。
 * 包むときに「表示名」も一緒に持たせておくと、
 * 画面で「〇〇さん、こんにちは」と出したり、
 * 誰が注文を進めたかを記録したりするのがラクになります。
 */
public class StaffUserDetails implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final String displayName;
    private final StaffRole role;
    private final boolean enabled;

    public StaffUserDetails(StaffUser user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.password = user.getPasswordHash();
        this.displayName = user.getDisplayName();
        this.role = user.getRole();
        this.enabled = user.isEnabled();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // ADMIN は STAFF ができることも全部できる、という設計にする
        if (role == StaffRole.ADMIN) {
            return List.of(new SimpleGrantedAuthority(StaffRole.ADMIN.authority()),
                    new SimpleGrantedAuthority(StaffRole.STAFF.authority()));
        }
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public StaffRole getRole() {
        return role;
    }

    public boolean isAdmin() {
        return role == StaffRole.ADMIN;
    }
}
