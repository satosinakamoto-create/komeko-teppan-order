package jp.komeko.order.cart;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * カートの 1 行（同じ商品・同じオプションの組み合わせをまとめた単位）。
 *
 * <p>「チーズ追加のガレット」と「素のガレット」は別の行になります。
 * 逆に、まったく同じ組み合わせを 2 回入れたら 1 行にまとまって個数が 2 になります。
 * その判定に使うのが {@link #key}（商品 ID + 選択肢 ID を並べた文字列）です。
 */
public class CartLine implements Serializable {

    /* 明示する理由は TableContext と同じ（保存されたセッションを読み戻せるように）。 */
    private static final long serialVersionUID = 1L;

    private final String key;
    private final Long menuItemId;
    private final String menuItemName;
    private final String imagePath;
    private final int basePrice;
    private final int cookMinutes;
    private final List<CartOption> options;
    private int quantity;

    public CartLine(Long menuItemId, String menuItemName, String imagePath,
                    int basePrice, int cookMinutes, List<CartOption> options, int quantity) {
        this.menuItemId = menuItemId;
        this.menuItemName = menuItemName;
        this.imagePath = imagePath;
        this.basePrice = basePrice;
        this.cookMinutes = cookMinutes;
        this.options = new ArrayList<>(options);
        this.quantity = quantity;
        this.key = buildKey(menuItemId, this.options);
    }

    /**
     * 同一性を判定するためのキーを作る。
     * 選択肢 ID を昇順に並べてから連結することで、
     * 選んだ順番が違っても同じキーになるようにしています。
     */
    private static String buildKey(Long menuItemId, List<CartOption> options) {
        String optionPart = options.stream()
                .map(CartOption::choiceId)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining("-"));
        return menuItemId + (optionPart.isEmpty() ? "" : ":" + optionPart);
    }

    /** オプション込みの単価。 */
    public int getUnitPrice() {
        return basePrice + options.stream().mapToInt(CartOption::extraPrice).sum();
    }

    /** この行の小計。 */
    public int getSubtotal() {
        return getUnitPrice() * quantity;
    }

    /** 「チーズ追加 / 大盛り」のような 1 行表示。 */
    public String getOptionSummary() {
        return options.stream().map(CartOption::choiceName).collect(Collectors.joining(" / "));
    }

    public void addQuantity(int delta) {
        this.quantity += delta;
    }

    public String getKey() {
        return key;
    }

    public Long getMenuItemId() {
        return menuItemId;
    }

    public String getMenuItemName() {
        return menuItemName;
    }

    public String getImagePath() {
        return imagePath;
    }

    public int getBasePrice() {
        return basePrice;
    }

    public int getCookMinutes() {
        return cookMinutes;
    }

    public List<CartOption> getOptions() {
        return options;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
