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

    // ========================================================================
    //  並び順
    // ========================================================================

    /**
     * 新しく足すものに付ける並び順（いまの最大 + 10）。
     *
     * <p><b>0 のままにしない理由</b><br>
     * 既定値が 0 だと、追加した品がメニューの<b>先頭に割り込みます</b>。
     * 看板の「肉玉米粉そば」の上に、試しに足した品が乗る。
     * 店主が気づいて直すまで、お客さまにはその並びで見えています。
     *
     * <p>10 ずつ空けるのは、あとから手で数字を入れて割り込ませたくなったとき
     * （「これは 3 番目に置きたい」）に、周りを詰め直さずに済むからです。
     *
     * @param used すでに使われている並び順。空なら 10 から始める
     */
    private static int nextSortOrder(List<Integer> used) {
        int max = 0;
        for (Integer v : used) {
            if (v != null && v > max) {
                max = v;
            }
        }
        return max + 10;
    }

    /** 新しいカテゴリに付ける並び順。 */
    @Transactional(readOnly = true)
    public int nextCategorySortOrder() {
        return nextSortOrder(categoryRepository.findAllByOrderBySortOrderAscIdAsc()
                .stream().map(Category::getSortOrder).toList());
    }

    /**
     * どのカテゴリに入れても末尾になる並び番号。
     *
     * <p>新規登録の画面では、まだカテゴリが選ばれていません。
     * それでも並びの欄は出す（設計 08-2）ので、開いた時点で
     * <b>意味のある数字</b>が入っている必要があります。
     * 0 や 10 を入れておくと、触らずに保存した品が
     * <b>看板メニューの上に割り込みます</b>。
     */
    @Transactional(readOnly = true)
    public int nextItemSortOrderAnywhere() {
        return nextSortOrder(menuItemRepository.findAllForAdmin()
                .stream().map(MenuItem::getSortOrder).toList());
    }

    /** そのカテゴリに新しく足す商品に付ける並び順。 */
    @Transactional(readOnly = true)
    public int nextItemSortOrder(Long categoryId) {
        if (categoryId == null) {
            return 10;
        }
        return nextSortOrder(menuItemRepository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId)
                .stream().map(MenuItem::getSortOrder).toList());
    }

    /**
     * カテゴリをひとつ上（または下）へ動かす。
     *
     * <p><b>数字を入れ替えるのではなく、隣と席を交換します。</b>
     * 「sortOrder を 1 減らす」だと、隣が 10 離れていれば何も起きず、
     * 同じ数字が並んでいれば順番が変わらないまま数字だけ動きます。
     * どちらも「押したのに動かない」に見えます。
     *
     * <p>並び替えたい人が見ているのは<b>画面に並んだ順</b>なので、
     * その順で 1 つ手前（奥）にいる相手を見つけて、数字を交換します。
     * 数字が同じで id 順で並んでいた場合も、交換したあと必ず差がつくように
     * 「同じなら片方をずらす」を入れてあります。
     *
     * @return 動かせたら true。すでに端なら false（押しても何も起きない）
     */
    @Transactional
    public boolean moveCategory(Long categoryId, boolean up) {
        List<Category> all = categoryRepository.findAllByOrderBySortOrderAscIdAsc();
        int index = indexOf(all, c -> c.getId().equals(categoryId));
        int neighbour = up ? index - 1 : index + 1;
        if (index < 0 || neighbour < 0 || neighbour >= all.size()) {
            return false;
        }
        Category a = all.get(index);
        Category b = all.get(neighbour);
        swapSortOrder(a.getSortOrder(), b.getSortOrder(), up,
                a::setSortOrder, b::setSortOrder);
        return true;
    }

    /**
     * 商品をひとつ上（または下）へ動かす。
     *
     * <p>並び替えるのは<b>同じカテゴリの中だけ</b>です。
     * カテゴリをまたいで動かすと、商品の所属が変わってしまいます
     * （カテゴリを変えたいときは編集フォームから変更してください）。
     */
    @Transactional
    public boolean moveItem(Long menuItemId, boolean up) {
        MenuItem item = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new MenuItemNotFoundException(menuItemId));
        List<MenuItem> siblings = menuItemRepository
                .findByCategoryIdOrderBySortOrderAscIdAsc(item.getCategory().getId());
        int index = indexOf(siblings, m -> m.getId().equals(menuItemId));
        int neighbour = up ? index - 1 : index + 1;
        if (index < 0 || neighbour < 0 || neighbour >= siblings.size()) {
            return false;
        }
        MenuItem a = siblings.get(index);
        MenuItem b = siblings.get(neighbour);
        swapSortOrder(a.getSortOrder(), b.getSortOrder(), up,
                a::setSortOrder, b::setSortOrder);
        return true;
    }

    private static <T> int indexOf(List<T> list, java.util.function.Predicate<T> match) {
        for (int i = 0; i < list.size(); i++) {
            if (match.test(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 隣り合う 2 つの並び順を入れ替える。
     *
     * <p>数字が同じときは交換しても順番が変わらない（id 順のままになる）ので、
     * 動かすほうを 1 だけずらして差をつけます。
     * 上へ動かすなら小さく、下へ動かすなら大きく。
     */
    private static void swapSortOrder(int mine, int theirs, boolean up,
                                      java.util.function.IntConsumer setMine,
                                      java.util.function.IntConsumer setTheirs) {
        if (mine == theirs) {
            setMine.accept(up ? mine - 1 : mine + 1);
            return;
        }
        setMine.accept(theirs);
        setTheirs.accept(mine);
    }

    /**
     * 品切れトグル（厨房からも商品一覧からもワンタップで叩ける）。
     *
     * <p><b>★ 価格の入っていない品の「販売再開」は断ります（2026-09-07）。</b><br>
     * {@link MenuItem#isOrderable()} は価格を見ません。掲載中かつ品切れでなければ
     * 注文できます。時価の品は価格 0 で登録し、品切れにしておくことで
     * お客さまからは注文できない状態にしています
     * （金額はスタッフが店舗端末で決めて入れる）。
     *
     * <p>つまりここで品切れを外すと、<b>¥0 のまま注文できる品</b>ができます。
     * 売上にも伝票にも 0 円で乗り、気づくのは会計のときです。
     * この危険は 2026-08-27 に商品一覧の同じボタンを消した理由でもありました。
     * ボタンを戻すにあたって、危険のほうをここで閉じます。
     * 1 か所で閉じれば、厨房の品切れパネルからも同じように守られます。
     */
    @Transactional
    public boolean toggleSoldOut(Long menuItemId) {
        MenuItem item = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new MenuItemNotFoundException(menuItemId));
        if (item.isSoldOut() && item.getPrice() <= 0) {
            throw new PriceNotSetException(item.getName());
        }
        item.setSoldOut(!item.isSoldOut());
        return item.isSoldOut();
    }

    /**
     * 掲載を切り替える（商品一覧からワンタップで叩ける）。
     *
     * <p>掲載を止めると、卓の QR から開くメニューにその品が出なくなります。
     * すでにカートに入れているお客さまのぶんは
     * {@code CartService#refresh} が落とします。
     *
     * <p>ここでは価格を見ません。掲載しても品切れのままなら注文はできないので、
     * {@link #toggleSoldOut} と違って ¥0 で売れてしまう経路が無いためです。
     */
    @Transactional
    public boolean toggleVisible(Long menuItemId) {
        MenuItem item = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new MenuItemNotFoundException(menuItemId));
        item.setVisible(!item.isVisible());
        return item.isVisible();
    }

    /** 価格が入っていないまま販売を再開しようとしたとき。 */
    public static class PriceNotSetException extends RuntimeException {
        private final String itemName;

        public PriceNotSetException(String itemName) {
            super("「%s」は価格が入っていません".formatted(itemName));
            this.itemName = itemName;
        }

        public String getItemName() {
            return itemName;
        }
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
