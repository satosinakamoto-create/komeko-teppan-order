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
     * カートの中身を「いまのメニュー情報」で洗い替える。
     *
     * <p>カートはセッションに置かれるため、入れてから注文するまでの間に
     * 値上げ・品切れ・商品削除が起きている可能性があります。
     * セッションの値をそのまま信じて会計すると、
     * 実際の売価と違う金額で受け付けてしまうので、注文直前に必ず通します。
     *
     * @return 変更点の説明（空ならカートは最新のまま）
     */
    @Transactional(readOnly = true)
    public List<String> refresh(Cart cart) {
        List<String> changes = new ArrayList<>();
        List<CartLine> rebuilt = new ArrayList<>();

        for (CartLine line : cart.getLines()) {
            MenuItem item;
            try {
                item = menuService.itemWithOptions(line.getMenuItemId());
            } catch (MenuService.MenuItemNotFoundException e) {
                changes.add("「%s」はメニューから削除されたため、カートから外しました".formatted(line.getMenuItemName()));
                continue;
            }
            if (!item.isOrderable()) {
                // 手動の品切れフラグでも残数ゼロでも、お客さまへの言葉は同じ「売り切れ」
                String reason = (item.isSoldOut() || item.isOutOfStock()) ? "売り切れ" : "取り扱い終了";
                changes.add("「%s」は%sのため、カートから外しました".formatted(item.getName(), reason));
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
        return changes;
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
