package jp.komeko.order.security;

import jp.komeko.order.repository.StaffUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ログイン時に「そのユーザー名の人は誰か」を DB から引いてくる係。
 *
 * <p>Spring Security は
 * <ol>
 *   <li>この {@link UserDetailsService} でユーザーを探し</li>
 *   <li>入力されたパスワードを {@code PasswordEncoder} でハッシュ化して</li>
 *   <li>DB のハッシュと一致するか比べる</li>
 * </ol>
 * という流れで認証します。パスワードの照合は Security 側がやってくれるので、
 * こちらは「ユーザーを探して返す」だけでよい、というのがポイントです。
 *
 * <p>ユーザーが見つからないときにわざと同じメッセージにしているのは、
 * 「そのユーザー名は存在する／しない」を攻撃者に教えないためです。
 */
@Service
public class StaffUserDetailsService implements UserDetailsService {

    private final StaffUserRepository repository;

    public StaffUserDetailsService(StaffUserRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return repository.findByUsername(username)
                .map(StaffUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("ユーザー名またはパスワードが違います"));
    }
}
