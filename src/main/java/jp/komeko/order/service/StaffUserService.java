package jp.komeko.order.service;

import jp.komeko.order.domain.StaffRole;
import jp.komeko.order.domain.StaffUser;
import jp.komeko.order.repository.StaffUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * スタッフアカウントの管理。
 */
@Service
public class StaffUserService {

    private final StaffUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public StaffUserService(StaffUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<StaffUser> findAll() {
        return repository.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public StaffUser getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("スタッフが見つかりません（id=%d）".formatted(id)));
    }

    /**
     * ログインできたときに、その時刻を残す（設計 13 スタッフの「最終ログイン」）。
     *
     * <p><b>失敗しても素通りします。</b>
     * ここで例外を投げると、記録が取れなかっただけでログインそのものが
     * 失敗します。最終ログインは「あとで見て役に立つ情報」であって、
     * 入れないと働けないものではありません。
     * 出勤して最初の 1 回に効くので、ここで転ぶと店が開きません。
     *
     * <p>見つからないユーザー名は黙って無視します。
     * 認証を通ったあとに呼ばれるので、ふつうは起きません。
     */
    @Transactional
    public void recordLogin(String username) {
        repository.findByUsername(username)
                .ifPresent(user -> user.setLastLoginAt(java.time.LocalDateTime.now()));
    }

    /**
     * 新規登録。パスワードはここで必ずハッシュ化する。
     *
     * @throws IllegalArgumentException ユーザー名が既に使われているとき
     */
    @Transactional
    public StaffUser create(String username, String rawPassword, String displayName, StaffRole role) {
        validatePassword(rawPassword);
        if (repository.existsByUsername(username)) {
            throw new IllegalArgumentException("そのユーザー名は既に使われています");
        }
        StaffUser user = new StaffUser(username, passwordEncoder.encode(rawPassword), displayName, role);
        return repository.save(user);
    }

    @Transactional
    public void update(Long id, String displayName, StaffRole role, boolean enabled) {
        StaffUser user = getById(id);
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setEnabled(enabled);
    }

    @Transactional
    public void changePassword(Long id, String rawPassword) {
        validatePassword(rawPassword);
        StaffUser user = getById(id);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
    }

    /**
     * 削除。管理者が 0 人にならないよう歯止めをかける。
     * （全員消してしまうと二度と管理画面に入れなくなるため）
     */
    @Transactional
    public void delete(Long id) {
        StaffUser user = getById(id);
        if (user.getRole() == StaffRole.ADMIN && countEnabledAdmins() <= 1) {
            throw new IllegalStateException("管理者が 0 人になるため削除できません");
        }
        repository.delete(user);
    }

    @Transactional(readOnly = true)
    public long countEnabledAdmins() {
        return repository.findAll().stream()
                .filter(u -> u.getRole() == StaffRole.ADMIN && u.isEnabled())
                .count();
    }

    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new IllegalArgumentException("パスワードは 8 文字以上にしてください");
        }
        if (rawPassword.length() > 72) {
            // BCrypt は 72 バイトを超える部分を無視するため、長すぎる入力は弾く
            throw new IllegalArgumentException("パスワードは 72 文字以内にしてください");
        }
    }
}
