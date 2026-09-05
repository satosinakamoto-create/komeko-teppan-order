package jp.komeko.order.service;

import jp.komeko.order.cart.Cart;
import jp.komeko.order.cart.CartLine;
import jp.komeko.order.cart.CartOption;
import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.domain.OptionChoice;
import jp.komeko.order.domain.OptionGroup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「商品ページで選んだ内容をカートに入れる」処理。
 *
 * <p><b>入力チェックはサーバ側でもやる</b><br>
 * 画面（HTML/JS）でも「必須のサイズを選んでね」と出しますが、
 * ブラウザの開発者ツールから直接 POST を送れば簡単に迂回できます。
 * 金額や品切れに関わる判断は必ずサーバ側で再チェックします。
 */
@Service
public class CartService {

    private final MenuService menuService;

    public CartService(MenuService menuService) {
        this.menuService = menuService;
    }

    /**
     * 商品をカートに追加する。
     *
     * @param cart        セッションのカート
     * @param menuItemId  商品 ID
     * @param choiceIds   画面で選ばれた選択肢の ID（何も選ばなければ空でよい）
     * @param quantity    個数
     * @throws OrderRejectedException 品切れ・選択数違反など、受け付けられないとき
     */
    @Transactional(readOnly = true)
    public CartLine addToCart(Cart cart, Long menuItemId, List<Long> choiceIds, int quantity) {
        if (quantity < 1) {
            throw new OrderRejectedException("個数は 1 以上を指定してください");
        }
        if (quantity > Cart.MAX_QUANTITY_PER_LINE) {
            throw new OrderRejectedException("一度に注文できるのは %d 個までです".formatted(Cart.MAX_QUANTITY_PER_LINE));
        }

        MenuItem item = menuService.itemWithOptions(menuItemId);
        if (!item.isVisible()) {
            throw new OrderRejectedException("「%s」は現在お取り扱いしていません".formatted(item.getName()));
        }
        if (item.isSoldOut() || item.isOutOfStock()) {
            throw new OrderRejectedException("「%s」は売り切れました".formatted(item.getName()));
        }
        // 残数管理している品は、この時点でも軽くチェックして早めに知らせる。
        // ただしこれは UX のための「早い警告」でしかない。
        // 本当の売り越え防止は注文確定時の条件付き UPDATE（OrderService 側）が行う。
        // ここで OK でも、確定までの間に他の卓が買っていく可能性は常にある。
        if (item.isStockTracked() && quantity > item.getStockRemaining()) {
            throw new OrderRejectedException(
                    "「%s」は残り %d 点です。数量を変更してください"
                            .formatted(item.getName(), item.getStockRemaining()));
        }

        // 重複を除いた選択 ID の集合（同じ ID が 2 回来ても 1 回として扱う）
        Set<Long> selected = new LinkedHashSet<>(choiceIds == null ? List.of() : choiceIds);

        List<CartOption> options = validateAndBuildOptions(item, selected);

        CartLine line = new CartLine(
                item.getId(),
                item.getName(),
                item.getImagePath(),
                item.getPrice(),
                item.getCookMinutes(),
                options,
                quantity);

        return cart.add(line);
    }

    /**
     * 選択肢の検証だけを行い、注文明細に載せられる形にして返す。
     *
     * <p>スタッフが卓に代わって注文を入れる経路（{@code OrderService#placeByStaff}）から
     * 呼ばれます。あちらはカートを持たない——1 品ずつその場で確定するので、
     * セッションに溜める入れ物が要りません。それでも
     * <b>「必須を選んだか」「上限を超えていないか」「この商品の選択肢か」の判断は
     * ここと同じでなければいけない</b>ので、検証の本体を共有しています。
     *
     * <p>スタッフ側で緩めると、お客さまの画面では通らない組み合わせが
     * 伝票に載ることになります。金額はオプションの追加料金を含むので、
     * それは請求額の食い違いとして表に出ます。
     *
     * @param item      商品（オプションを読み込み済みのもの）
     * @param choiceIds 選ばれた選択肢の ID。null や空でもよい
     * @throws OrderRejectedException 必須未選択・上限超過・別商品の選択肢が混ざっているとき
     */
    public List<CartOption> validateOptions(MenuItem item, List<Long> choiceIds) {
        Set<Long> selected = new LinkedHashSet<>(choiceIds == null ? List.of() : choiceIds);
        return validateAndBuildOptions(item, selected);
    }

    /**
     * カートを洗い替えた結果。
     *
     * <p><b>変更を 2 種類に分けている理由</b><br>
     * もとは変更をひとまとめの {@code List<String>} で返していて、
     * 1 つでもあれば注文を丸ごと差し戻していました。
     * そのため<b>4 品のうち 1 品が売り切れただけで、残り 3 品も通らない</b>という
     * 挙動になっていました。しかも売り切れは、他の卓が最後の 1 点を買っただけで
     * 起こります。誰も管理画面を触っていなくても起こる、ごく普通のことです。
     *
     * <p>そこで「もうその品は出せない」と「出せるけれど中身が違う」を分けました。
     *
     * <ul>
     *   <li>{@link #removed} … 売り切れ・残数ゼロ・取り扱い終了・削除済み。
     *       <b>落として先に進んでよい</b>。その品が無いだけで、他の品には影響しない。</li>
     *   <li>{@link #needsConfirm} … 価格やオプションの中身が変わった。
     *       <b>止めて確認してもらう</b>。黙って通すと、
     *       お客さまが画面で見ていない金額で確定してしまう。</li>
     * </ul>
     *
     * <p>運用としては「価格を変えるときは、いったん販売中止にしてから変える」と
     * 決めてあります。そうすれば価格が動く瞬間には誰も注文できないので、
     * {@code needsConfirm} は実際にはめったに埋まりません。
     * それでも残してあるのは、手順を飛ばしたときに
     * 金額のほうが黙って通ってしまわないようにするためです。
     *
     * @param removed      落とした品の説明（お客さまにそのまま見せる文）
     * @param needsConfirm 確認が要る変更の説明
     */
    public record CartRefresh(List<String> removed, List<String> needsConfirm) {

        /** 何も変わっていなければ true。 */
        public boolean isUnchanged() {
            return removed.isEmpty() && needsConfirm.isEmpty();
        }
    }

    /**
     * カートの中身を「いまのメニュー情報」で洗い替える。
     *
     * <p>カートはセッションに置かれるため、入れてから注文するまでの間に
     * 値上げ・品切れ・商品削除が起きている可能性があります。
     * セッションの値をそのまま信じて会計すると、
     * 実際の売価と違う金額で受け付けてしまうので、注文直前に必ず通します。
     *
     * @return 落とした品と、確認が要る変更（{@link CartRefresh} 参照）
     */
    @Transactional(readOnly = true)
    public CartRefresh refresh(Cart cart) {
        // 落とした品。これがあっても注文は先に進む
        List<String> removed = new ArrayList<>();
        // 金額や中身が変わったもの。これがあると注文は止まる
        List<String> changes = new ArrayList<>();
        List<CartLine> rebuilt = new ArrayList<>();

        for (CartLine line : cart.getLines()) {
            MenuItem item;
            try {
                item = menuService.itemWithOptions(line.getMenuItemId());
            } catch (MenuService.MenuItemNotFoundException e) {
                removed.add("「%s」はメニューから削除されたため、カートから外しました".formatted(line.getMenuItemName()));
                continue;
            }
            if (!item.isOrderable()) {
                // 手動の品切れフラグでも残数ゼロでも、お客さまへの言葉は同じ「売り切れ」
                String reason = (item.isSoldOut() || item.isOutOfStock()) ? "売り切れ" : "取り扱い終了";
                removed.add("「%s」は%sのため、カートから外しました".formatted(item.getName(), reason));
                continue;
            }
            if (item.getPrice() != line.getBasePrice()) {
                changes.add("「%s」の価格が %,d円 → %,d円 に変わりました"
                        .formatted(item.getName(), line.getBasePrice(), item.getPrice()));
            }

            // 選択済みオプションを最新の情報で作り直す
            List<CartOption> options = new ArrayList<>();
            boolean optionDropped = false;
            for (CartOption old : line.getOptions()) {
                OptionChoice choice = findChoice(item, old.choiceId());
                if (choice == null) {
                    changes.add("「%s」のオプション「%s」が無くなったため外しました"
                            .formatted(item.getName(), old.choiceName()));
                    optionDropped = true;
                    continue;
                }
                if (choice.isSoldOut()) {
                    changes.add("「%s」のオプション「%s」が品切れのため外しました"
                            .formatted(item.getName(), choice.getName()));
                    optionDropped = true;
                    continue;
                }
                if (choice.getExtraPrice() != old.extraPrice()) {
                    changes.add("「%s」のオプション「%s」の料金が変わりました"
                            .formatted(item.getName(), choice.getName()));
                }
                options.add(new CartOption(choice.getId(),
                        choice.getOptionGroup() != null ? choice.getOptionGroup().getName() : old.groupName(),
                        choice.getName(), choice.getExtraPrice()));
            }

            // 必須オプションが落ちてしまった場合は選び直してもらう
            if (optionDropped && hasUnsatisfiedRequiredGroup(item, options)) {
                changes.add("「%s」は必須の選択肢が変わったため、選び直してください".formatted(item.getName()));
                continue;
            }

            rebuilt.add(new CartLine(item.getId(), item.getName(), item.getImagePath(),
                    item.getPrice(), item.getCookMinutes(), options, line.getQuantity()));
        }

        cart.replaceAll(rebuilt);
        return new CartRefresh(List.copyOf(removed), List.copyOf(changes));
    }

    private OptionChoice findChoice(MenuItem item, Long choiceId) {
        if (choiceId == null) {
            return null;
        }
        for (OptionGroup group : item.getOptionGroups()) {
            for (OptionChoice choice : group.getChoices()) {
                if (choiceId.equals(choice.getId())) {
                    return choice;
                }
            }
        }
        return null;
    }

    private boolean hasUnsatisfiedRequiredGroup(MenuItem item, List<CartOption> options) {
        Set<Long> selected = options.stream().map(CartOption::choiceId).collect(java.util.stream.Collectors.toSet());
        for (OptionGroup group : item.getOptionGroups()) {
            if (!group.isRequired()) {
                continue;
            }
            long count = group.getChoices().stream().filter(c -> selected.contains(c.getId())).count();
            if (count < group.getMinSelect()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 選択肢がこの商品のものか、必須・上限を満たしているかを検証し、
     * 問題なければカート用のオプションリストに変換する。
     *
     * <p><b>★ 同じ選択肢を複数選べるようにするときは、ここから直すこと（2026-09-05）</b><br>
     * 保存の側は用意してあります（{@link jp.komeko.order.domain.OrderLineOption#getQuantity()}、
     * {@link OptionGroup#isAllowDuplicate()}）。
     * <b>ただし入口が {@code Set<Long>} なので、そもそも同じ id を 2 つ渡せません。</b>
     * 「ソース 3 つ」を通すには、ここを id → 個数 の形で受け取るように変える必要があります。
     *
     * <p>あわせて数え方も変わります。いまの {@code chosen.size()} は<b>何種類選んだか</b>で、
     * {@code allowDuplicate} が立つグループでは<b>合計で何個選んだか</b>を見るべきです。
     * 「4 種類」と「4 個」は、複数選べるようになった時点で別の意味になります。
     *
     * <p>いまは {@code allowDuplicate} を true にする手段が無いので、この経路は動きません。
     * 画面を作るときに、ここと {@code CartOption} を一緒に直してください。
     */
    private List<CartOption> validateAndBuildOptions(MenuItem item, Set<Long> selectedIds) {
        List<CartOption> result = new ArrayList<>();
        Set<Long> remaining = new LinkedHashSet<>(selectedIds);
        List<String> errors = new ArrayList<>();

        for (OptionGroup group : item.getOptionGroups()) {
            List<OptionChoice> chosen = group.getChoices().stream()
                    .filter(c -> selectedIds.contains(c.getId()))
                    .toList();

            chosen.forEach(c -> remaining.remove(c.getId()));

            if (chosen.size() < group.getMinSelect()) {
                errors.add("「%s」を %d 個以上お選びください".formatted(group.getName(), group.getMinSelect()));
                continue;
            }
            if (chosen.size() > group.getMaxSelect()) {
                errors.add("「%s」は %d 個までしか選べません".formatted(group.getName(), group.getMaxSelect()));
                continue;
            }
            for (OptionChoice choice : chosen) {
                if (choice.isSoldOut()) {
                    errors.add("「%s」は品切れです".formatted(choice.getName()));
                    continue;
                }
                result.add(new CartOption(
                        choice.getId(), group.getName(), choice.getName(), choice.getExtraPrice()));
            }
        }

        // この商品に属さない選択肢 ID が混ざっていたら、改ざんの可能性があるので弾く
        if (!remaining.isEmpty()) {
            errors.add("選択内容が正しくありません。お手数ですが最初からやり直してください");
        }

        if (!errors.isEmpty()) {
            throw new OrderRejectedException(errors);
        }
        return result;
    }
}
