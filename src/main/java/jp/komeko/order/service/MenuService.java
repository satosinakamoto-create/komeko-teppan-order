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
    /**
     * レシピの後始末用（在庫モジュール側のリポジトリ）。
     *
     * <p>{@code ObjectProvider} で受けるのは、在庫モジュールの都合で
     * この Bean が条件付きにされても、商品の削除まで壊れないようにするため
     * （{@code AdminSalesController} の {@code PurchaseService} と同じ受け方）。
     * 無いときはレシピの後始末を飛ばすだけでよい——リポジトリが
     * 存在しなかった環境には、消すべきレシピ行も存在しないからです。
     */
    private final org.springframework.beans.factory.ObjectProvider<jp.komeko.order.inventory.repository.RecipeLineRepository> recipeLineRepositoryProvider;
    private final org.springframework.beans.factory.ObjectProvider<jp.komeko.order.inventory.repository.RecipeOtherCostRepository> recipeOtherCostRepositoryProvider;

    public MenuService(CategoryRepository categoryRepository, MenuItemRepository menuItemRepository,
                       org.springframework.beans.factory.ObjectProvider<jp.komeko.order.inventory.repository.RecipeLineRepository> recipeLineRepositoryProvider,
                       org.springframework.beans.factory.ObjectProvider<jp.komeko.order.inventory.repository.RecipeOtherCostRepository> recipeOtherCostRepositoryProvider) {
        this.categoryRepository = categoryRepository;
        this.menuItemRepository = menuItemRepository;
        this.recipeLineRepositoryProvider = recipeLineRepositoryProvider;
        this.recipeOtherCostRepositoryProvider = recipeOtherCostRepositoryProvider;
    }

    // ========================================================================
    //  商品の削除
    // ========================================================================

    /**
     * 削除した商品の控え。フラッシュメッセージと画像の後始末に使う。
     *
     * @param name        商品名
     * @param imagePath   画像の公開パス。無ければ null
     * @param recipeLines 一緒に消したレシピの行数。0 なら文言に出さない
     */
    public record DeletedItem(String name, String imagePath, int recipeLines) {
    }

    /**
     * 商品を削除する。<b>レシピも一緒に消す。</b>
     *
     * <p>{@code recipe_line.menu_item_id} は NOT NULL の外部キー（V4）なので、
     * レシピ行を残したまま商品を消すとコミット時に FK 違反で落ちます
     * （2026-09-07 の全体点検で発覚。レシピ付き商品は削除できなかった）。
     * カテゴリや卓のような「止める門」にはしません。レシピは原価計算のための
     * 付属データで、商品が消えるなら一緒に消えるのが自然だからです。
     * ただし黙って消さず、消した行数を返して画面に知らせます。
     *
     * <p><b>画像ファイルはここでは消しません。</b>ファイル削除はロールバック
     * できないので、このトランザクションが確定したあとに呼び出し側で消します。
     * 逆順にすると「DB は失敗して商品が残ったのに、写真だけ消えた」が起きます
     * （実際に起きる状態だった）。
     */
    @Transactional
    public DeletedItem deleteItem(Long id) {
        MenuItem item = menuItemRepository.findById(id)
                .orElseThrow(() -> new MenuItemNotFoundException(id));

        int recipeLines = 0;
        jp.komeko.order.inventory.repository.RecipeLineRepository recipes =
                recipeLineRepositoryProvider.getIfAvailable();
        if (recipes != null) {
            recipeLines = recipes.findByMenuItem(id).size();
            if (recipeLines > 0) {
                recipes.deleteByMenuItemId(id);
            }
        }

        // ★ その他材料費も先に消す。外部キーに ON DELETE CASCADE を付けていないので、
        //   残したまま商品を消すと外部キー違反で削除そのものが失敗する
        //   （「その他材料費を入れた商品だけ消せない」という形で跳ね返る）。
        var otherCosts = recipeOtherCostRepositoryProvider.getIfAvailable();
        if (otherCosts != null) {
            otherCosts.deleteByMenuItemId(id);
        }

        String name = item.getName();
        String imagePath = item.getImagePath();
        menuItemRepository.delete(item);
        return new DeletedItem(name, imagePath, recipeLines);
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

    // ========================================================================
    //  カテゴリの編集画面から商品を出し入れする（2026-09-20）
    //
    //  店主の指示「カテゴリーで編集するボタン追加で商品名を追加、削除出来るように」
    //  → 話し合って「削除」ではなく「別のカテゴリへ移す」になりました。
    //    商品は必ずどこか 1 つのカテゴリに属するので（category_id は NOT NULL）、
    //    「ここに足す」＝「よそから移す」です。消す操作は商品の画面に任せます。
    // ========================================================================

    /**
     * 大分類（メニューのタブ名）を、重複を落として並べる。
     *
     * <p>カテゴリの追加・編集で {@code <select>} に出す選択肢です。
     * <b>専用のテーブルは作っていません。</b>{@code Category.groupName} を舐めるだけです。
     * 十数件しかないので、これで足ります。
     *
     * <p>★ {@code getTabName()} ではなく {@code getGroupName()} を使うこと。
     * 前者は大分類が未設定のときカテゴリ名を返すので、
     * 「広島風お好み焼き」がタブの候補として並んでしまいます。
     *
     * <p>並び順はカテゴリの並び順のまま（{@code LinkedHashSet}）。
     * 名前順に並べ替えると、お客さまのメニューのタブの並びと食い違います。
     */
    @Transactional(readOnly = true)
    public List<String> groupNames() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (Category c : allCategories()) {
            String g = c.getGroupName();
            if (g != null && !g.isBlank()) {
                names.add(g);
            }
        }
        return List.copyOf(names);
    }

    /**
     * 名前だけの「書きかけ」を 1 件作る。
     *
     * <p>価格はあとから商品の画面で入れます。
     *
     * <h2>★ draft と visible を両方落とすこと</h2>
     *
     * <p>{@link MenuItem} の初期値は {@code visible=true} / {@code draft=false} です。
     * <b>どちらか片方だけでは、お客さまの画面に出ます。</b>
     *
     * <pre>
     *   draft しか見ていない門  … findVisibleForCustomer の where、isOrderable()
     *   visible しか見ていない門 … MenuController.item、itemsForSoldOutPanel
     * </pre>
     *
     * <p>2026-09-07 に「書きかけ」を実際にお客さまのメニューへ出しました。
     * {@code isOrderable()} に {@code !draft} を足しただけで安心し、
     * お客さまのメニューの問い合わせがそこを通っていないことを見落としたためです。
     *
     * <p>★ 並び順も必ず付けること。0 のままだと、
     * 名前を打っただけの品が看板メニューの上に割り込みます。
     */
    @Transactional
    public MenuItem createDraftItem(Long categoryId, String name) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("カテゴリが見つかりません: " + categoryId));
        MenuItem item = new MenuItem(category, name.trim(), 0);
        item.setDraft(true);
        item.setVisible(false);
        item.setSortOrder(nextItemSortOrder(categoryId));
        return menuItemRepository.save(item);
    }

    /**
     * 選んだ商品を、まとめて別のカテゴリへ移す。
     *
     * <h2>★ 並び番号は 1 回だけ読んで、自分で進めること</h2>
     *
     * <p>{@code nextItemSortOrder} をループの中で呼び直すと、
     * 商品の数だけ {@code @EntityGraph} 付きの SELECT が飛びます。
     * かといって 1 回取った値を全員に使うと<b>全員が同じ番号</b>になり、
     * 行き先でドラッグしても上下ボタンを押しても順番が決まりません
     * （押しても何も起きないだけで、例外は出ません）。
     * 1 回取って {@code +10} ずつ自分で進めるのが正解です。
     *
     * <p>★ すでに行き先にいる商品は飛ばします。飛ばさないと
     * {@code nextItemSortOrder} がその商品自身を含んだ最大値を返し、
     * 「移していないのに自分のカテゴリの末尾へ黙って飛ぶ」ことになります。
     *
     * <p>元のカテゴリに空いた番号は詰め直しません（10, 40 のように飛んで構いません）。
     * 順番は数字の大小で決まるので、詰める必要がないためです。
     *
     * @return 実際に移した件数
     */
    @Transactional
    public int moveItemsToCategory(List<Long> itemIds, Long targetCategoryId) {
        if (itemIds == null || itemIds.isEmpty() || targetCategoryId == null) {
            return 0;
        }
        Category target = categoryRepository.findById(targetCategoryId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "移す先のカテゴリが見つかりません: " + targetCategoryId));

        int next = nextItemSortOrder(targetCategoryId);
        int moved = 0;
        for (Long id : itemIds) {
            MenuItem item = menuItemRepository.findById(id).orElse(null);
            if (item == null || targetCategoryId.equals(item.getCategory().getId())) {
                continue;                       // すでに行き先にいるものは触らない
            }
            item.setCategory(target);
            item.setSortOrder(next);
            next += 10;
            moved++;
        }
        return moved;
    }

    /**
     * 同じ名前の商品を探す（見つからなければ null）。
     *
     * <p>カテゴリの編集画面で名前を打ったとき、
     * 別のカテゴリに同じ品がないかを確かめるために使います。
     *
     * <h2>なぜ {@code findFirstByNameIgnoreCase} を使わないか</h2>
     *
     * <p>Spring Data が吐くのは {@code upper(name) = upper(?)} で、日本語には効きません。
     * さらに dev（H2）と本番（PostgreSQL）で照合順序が違うので、
     * 手元で通ったものが本番で通らない形の食い違いを抱えます。
     *
     * <h2>なぜ {@code AliasText.normalize} を使わないか</h2>
     *
     * <p>あちらはレシートの品名を食材へ名寄せするための正規化で、
     * {@code ()（）・-} などの記号を落とします。ここに使うと
     * <b>「生ビール（中）」と「生ビール中」が同名扱い</b>になり、正しい商品を作れません。
     * 名寄せは「寄せたい」、重複判定は「区別したい」で、目的が逆です。
     *
     * <p>ここでは全角半角（NFKC）・大文字小文字・前後の空白だけを吸収します。
     */
    @Transactional(readOnly = true)
    public MenuItem findSameNameItem(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String want = normalizeItemName(name);
        for (MenuItem item : menuItemRepository.findAllForAdmin()) {
            if (want.equalsIgnoreCase(normalizeItemName(item.getName()))) {
                return item;
            }
        }
        return null;
    }

    private static String normalizeItemName(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).trim();
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

    /**
     * 商品を、同じカテゴリの中の好きな位置へ動かす（2026-09-19）。
     *
     * <p>店主の指示「並び順はドラック＆ドロップで入れ替えられる仕様にしたいかな、
     * その方が直感的だし 1 つづつずらして行く必要ないし」。
     * {@link #moveItem(Long, boolean)} が隣と 1 つ入れ替えるのに対し、
     * こちらは<b>離れた場所へ一度に</b>動かします。
     *
     * <p><b>カテゴリはまたげません。</b>並び順の値はカテゴリごとに独立していて、
     * 一覧も「カテゴリ順 → 並び順」で並んでいます。別のカテゴリの位置へ動かすのは
     * 「カテゴリを変える」ことなので、それは編集フォームの仕事です。
     * 相手が別のカテゴリなら何もせずに {@code false} を返します。
     *
     * <p><b>並び順は 10 きざみで振り直します。</b>入れ替えのたびに 1 ずつ
     * ずらしていくと、いつか隣同士の数字が同じになって順番が決まらなくなります
     * （{@code swapSortOrder} の注意書きと同じ話）。
     * 動かした列だけ通しで振り直すほうが、あとから読んでも分かります。
     *
     * <p><b>行き先は「どの商品の隣か」で指します。</b>
     * {@code beforeId} があればその直前、無ければ {@code afterId} の直後。
     *
     * <p>2026-09-19 に「相手が {@code null} なら先頭」という決め方をやめました。
     * 理由は 2 つあります。
     *
     * <ol>
     *   <li><b>下端に落としたときに送るものが無くなる。</b>その行の次が無いので
     *       画面は {@code null} を送り、先頭へ飛んでいました。</li>
     *   <li><b>絞り込んでいると「いちばん下」が曖昧になる。</b>タブやカテゴリで
     *       隠れている同じカテゴリの商品が、見えている最後の行のさらに下にいる
     *       ことがあります。「いちばん下」と言われても、<b>見えている下</b>なのか
     *       <b>本当の下</b>なのか決められません。</li>
     * </ol>
     *
     * <p>隣の商品を指せば、隠れている行が何行あっても<b>落とした場所どおり</b>になります。
     * これが、絞り込み中でも並べ替えを許せる理由です。
     *
     * @param menuItemId 動かす商品
     * @param beforeId   この商品の<b>直前</b>に入れる
     * @param afterId    {@code beforeId} が無いとき、この商品の<b>直後</b>に入れる
     * @return 動かせたら true。相手が見つからない・別カテゴリ・動かす必要が無いときは false
     */
    @Transactional
    public boolean placeItemNextTo(Long menuItemId, Long beforeId, Long afterId) {
        MenuItem item = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new MenuItemNotFoundException(menuItemId));
        Long categoryId = item.getCategory().getId();

        List<MenuItem> siblings = new java.util.ArrayList<>(
                menuItemRepository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId));

        // ★ 並べ替えそのものは SortOrderPlacer にまとめてあります（カテゴリ・卓と共通）。
        //   ここでやるのは「同じカテゴリの中だけ」という境界の見張りだけです。
        return SortOrderPlacer.place(siblings, menuItemId,
                MenuItem::getId, MenuItem::setSortOrder, beforeId, afterId);
    }

    /**
     * カテゴリを、好きな位置へ動かす（2026-09-19、店主の指示
     * 「商品、カテゴリー、卓にもドラッグ＆ドロップ実装してほしい」）。
     *
     * <p>商品の {@link #placeItemNextTo} と同じ考え方です。行き先は
     * 「どの並びの隣か」で指します——{@code beforeId} があればその直前、
     * 無ければ {@code afterId} の直後。
     *
     * <p><b>カテゴリは 1 本の並びです。</b>商品のような「またげない境界」はありません。
     *
     * <p>並び順は 10 きざみで振り直します。1 ずつずらしていくと、
     * いつか隣同士の数字が同じになって順番が決まらなくなります。
     *
     * @return 動かせたら true。相手が見つからない・動かす必要が無いときは false
     */
    @Transactional
    public boolean placeCategoryNextTo(Long categoryId, Long beforeId, Long afterId) {
        List<Category> all =
                new java.util.ArrayList<>(categoryRepository.findAllByOrderBySortOrderAscIdAsc());
        return SortOrderPlacer.place(all, categoryId,
                Category::getId, Category::setSortOrder, beforeId, afterId);
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
