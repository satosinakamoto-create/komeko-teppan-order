package jp.komeko.order.cart;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 買い物カゴ。
 *
 * <p><b>{@code @SessionScope} とは</b><br>
 * 通常の Bean はアプリ全体で 1 個（シングルトン）ですが、
 * これを付けると<b>ブラウザのセッションごとに 1 個</b>作られます。
 * つまりお客さん A のカートと B のカートが混ざりません。
 *
 * <p>Spring は内部でプロキシ（身代わりオブジェクト）を挟むことでこれを実現しています。
 * そのためコントローラにはシングルトンとして注入してよく、
 * 実際にメソッドを呼んだ瞬間に「その人のカート」に転送されます。
 *
 * <p>上限（{@link #MAX_LINES} / {@link #MAX_QUANTITY_PER_LINE}）を設けているのは、
 * いたずらで数万個入れられてセッションのメモリを食い潰されるのを防ぐためです。
 */
@Component
@SessionScope
public class Cart implements Serializable {

    /** カートに入れられる行数の上限。 */
    public static final int MAX_LINES = 30;
    /** 1 行あたりの個数の上限。 */
    public static final int MAX_QUANTITY_PER_LINE = 20;

    private final List<CartLine> lines = new ArrayList<>();

    /** 呼び出し用ニックネーム（任意）。 */
    private String customerName;

    /** 店への要望。 */
    private String note;

    /**
     * 商品を追加する。同じ内容の行がすでにあれば個数を足す。
     *
     * @return 追加後のその行
     */
    public CartLine add(CartLine newLine) {
        Optional<CartLine> existing = findByKey(newLine.getKey());
        if (existing.isPresent()) {
            CartLine line = existing.get();
            int merged = Math.min(line.getQuantity() + newLine.getQuantity(), MAX_QUANTITY_PER_LINE);
            line.setQuantity(merged);
            return line;
        }
        if (lines.size() >= MAX_LINES) {
            throw new IllegalStateException("カートに入れられる品数の上限（%d種類）に達しました".formatted(MAX_LINES));
        }
        newLine.setQuantity(Math.min(newLine.getQuantity(), MAX_QUANTITY_PER_LINE));
        lines.add(newLine);
        return newLine;
    }

    /** 個数を変更する。0 以下なら行ごと削除。 */
    public void changeQuantity(String key, int quantity) {
        if (quantity <= 0) {
            remove(key);
            return;
        }
        findByKey(key).ifPresent(line -> line.setQuantity(Math.min(quantity, MAX_QUANTITY_PER_LINE)));
    }

    public void remove(String key) {
        lines.removeIf(line -> line.getKey().equals(key));
    }

    public void clear() {
        lines.clear();
        customerName = null;
        note = null;
    }

    /**
     * 中身をまるごと入れ替える。
     * 「注文直前に最新のメニュー情報で価格を洗い替える」処理で使います
     * （{@code CartService#refresh}）。
     */
    public void replaceAll(List<CartLine> newLines) {
        lines.clear();
        lines.addAll(newLines);
    }

    private Optional<CartLine> findByKey(String key) {
        return lines.stream().filter(line -> line.getKey().equals(key)).findFirst();
    }

    /** 変更されないようコピーを返す（外から勝手に add されるのを防ぐ）。 */
    public List<CartLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** 合計金額（税込・円）。 */
    public int getTotalAmount() {
        return lines.stream().mapToInt(CartLine::getSubtotal).sum();
    }

    /** 合計点数（バッジに出す数字）。 */
    public int getTotalQuantity() {
        return lines.stream().mapToInt(CartLine::getQuantity).sum();
    }

    /** 調理時間の合計見込み（分）。 */
    public int getTotalCookMinutes() {
        return lines.stream().mapToInt(l -> l.getCookMinutes() * l.getQuantity()).sum();
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
