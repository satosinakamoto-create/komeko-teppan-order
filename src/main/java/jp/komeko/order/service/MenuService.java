package jp.komeko.order.service;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.OptionGroup;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.MenuItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * メニューの参照系サービス。
 *
 * <p><b>{@code @Transactional(readOnly = true)} を付ける理由</b><br>
 * 参照だけのメソッドに付けると、
 * <ul>
 *   <li>DB へ「読むだけ」と伝わり、余計なロックや更新チェックが省かれて速くなる</li>
 *   <li>このメソッドの中でだけ遅延読み込み（LAZY）を安全に解決できる</li>
 * </ul>
 * という利点があります。
 *
 * <p>このアプリは {@code open-in-view: false}（画面描画中は DB 接続を持たない）なので、
 * <b>必要な関連はこのサービスの中で読み終えてから返す</b>必要があります。
 * それを行っているのが {@code hydrate...} という名前のメソッドです。
 */
@Service
public class MenuService {

    private final CategoryRepository categoryRepository;
    private final MenuItemRepository menuItemRepository;

    public MenuService(CategoryRepository categoryRepository, MenuItemRepository menuItemRepository) {
        this.categoryRepository = categoryRepository;
        this.menuItemRepository = menuItemRepository;
    }

    /** お客さん向け：表示 ON のカテゴリ一覧。 */
    @Transactional(readOnly = true)
    public List<Category> visibleCategories() {
        return categoryRepository.findByVisibleTrueOrderBySortOrderAscIdAsc();
    }

    /** 管理画面向け：全カテゴリ。 */
    @Transactional(readOnly = true)
    public List<Category> allCategories() {
        return categoryRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    /**
     * お客さん向けメニューを「カテゴリ → 商品リスト」の形で返す。
     *
     * <p>{@link LinkedHashMap} を使うと <b>入れた順番が保たれます</b>。
     * 普通の {@code HashMap} は順不同なので、並び順が意味を持つ画面では使えません。
     */
    @Transactional(readOnly = true)
    public Map<Category, List<MenuItem>> customerMenu() {
        List<MenuItem> items = menuItemRepository.findVisibleForCustomer();
        Map<Category, List<MenuItem>> grouped = new LinkedHashMap<>();
        for (MenuItem item : items) {
            grouped.computeIfAbsent(item.getCategory(), k -> new java.util.ArrayList<>()).add(item);
        }
        return grouped;
    }

    /** 管理画面向け：全商品（非表示・品切れ含む）。 */
    @Transactional(readOnly = true)
    public List<MenuItem> allItemsForAdmin() {
        return menuItemRepository.findAllForAdmin();
    }

    /**
     * 商品詳細（オプションまで読み込んだ状態）。
     *
     * @throws MenuItemNotFoundException 商品が無いとき
     */
    @Transactional(readOnly = true)
    public MenuItem itemWithOptions(Long id) {
        MenuItem item = menuItemRepository.findByIdWithOptions(id)
                .orElseThrow(() -> new MenuItemNotFoundException(id));
        hydrateOptions(item);
        return item;
    }

    /**
     * オプションの選択肢まで読み込んでおく。
     *
     * <p>{@code size()} を呼ぶだけで LAZY な関連が実際に DB から読まれます。
     * この一手間を入れないと、画面描画時に
     * {@code LazyInitializationException} が出ます（Java + JPA の定番のつまずき）。
     */
    private void hydrateOptions(MenuItem item) {
        for (OptionGroup group : item.getOptionGroups()) {
            group.getChoices().size();
        }
    }

    /** カテゴリ内の商品数（削除前チェック用）。 */
    @Transactional(readOnly = true)
    public long countItemsInCategory(Long categoryId) {
        return menuItemRepository.countByCategoryId(categoryId);
    }

    /** 品切れトグル（厨房からもワンタップで叩ける）。 */
    @Transactional
    public boolean toggleSoldOut(Long menuItemId) {
        MenuItem item = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new MenuItemNotFoundException(menuItemId));
        item.setSoldOut(!item.isSoldOut());
        return item.isSoldOut();
    }

    /** 厨房の品切れ管理パネル用：注文可能性に関係する商品を並べて返す。 */
    @Transactional(readOnly = true)
    public List<MenuItem> itemsForSoldOutPanel() {
        return menuItemRepository.findAllForAdmin().stream()
                .filter(MenuItem::isVisible)
                .collect(Collectors.toList());
    }

    // ========================================================================
    //  残数（在庫）管理
    // ========================================================================

    /**
     * 残数を引く。足りなければ false（何も変更されない）。
     *
     * <p>実体は条件付き UPDATE（{@code MenuItemRepository#tryDecrementStock}）。
     * 同時注文でも最後の 1 個を 2 人に売らないことは DB が保証する。
     * 在庫を管理していない商品（null）は常に成功する。
     */
    @Transactional
    public boolean tryConsumeStock(Long menuItemId, int quantity) {
        return menuItemRepository.tryDecrementStock(menuItemId, quantity) == 1;
    }

    /** キャンセルで残数を戻す。在庫管理していない商品・削除済みの商品は素通り。 */
    @Transactional
    public void restoreStock(Long menuItemId, int quantity) {
        menuItemRepository.restoreStock(menuItemId, quantity);
    }

    /** いまの残数（null = 在庫管理していない）。エラーメッセージの組み立てに使う。 */
    @Transactional(readOnly = true)
    public Integer stockRemainingOf(Long menuItemId) {
        return menuItemRepository.findById(menuItemId)
                .map(MenuItem::getStockRemaining)
                .orElse(null);
    }

    /**
     * 残数を設定する（厨房の品切れパネルから使う）。
     *
     * @param count 本日の数。null で「在庫管理をやめる（無制限に戻す）」
     * @return 商品名（フラッシュメッセージ用）
     */
    @Transactional
    public String setStock(Long menuItemId, Integer count) {
        if (count != null && count < 0) {
            throw new IllegalArgumentException("残数は 0 以上で入力してください");
        }
        MenuItem item = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new MenuItemNotFoundException(menuItemId));
        item.setStockRemaining(count);
        return item.getName();
    }

    /** 商品が見つからないときの例外。 */
    public static class MenuItemNotFoundException extends RuntimeException {
        public MenuItemNotFoundException(Long id) {
            super("商品が見つかりません（id=%s）".formatted(id));
        }
    }
}
