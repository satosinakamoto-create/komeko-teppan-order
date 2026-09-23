package jp.komeko.order.inventory.web.form;

import jakarta.validation.Valid;

import java.util.ArrayList;
import java.util.List;

/**
 * 取り込み確認画面の商品カード 1 枚（設計 ト09b 807:1688）。
 *
 * <p>Figma の 1 カードに対応します。
 * <pre>
 *   商品の照合 … readName（読んだ商品名）＋ menuItemId（照合先）
 *   バッジ     … 画面側で判定（商品と一致／商品が見つかりません）
 *   材料の表   … lines
 *   ＋ 材料を足す
 * </pre>
 *
 * <p><b>予備の空行を最初から並べておきます。</b>これがレシート確認画面から
 * 受け継いだ「JavaScript なしで行を増やす」3 点セットの 1 つ目です
 * （予備空行 → {@code isBlank()} → {@code filledLines()}）。
 */
public class RecipeImportItemForm {

    /** 手入力で開いたときに並べる空行の数。多すぎると画面が間延びする。 */
    private static final int SPARE_LINES = 3;

    /** ノート・エクセルにそう書いてあった商品名。表示専用。 */
    private String readName;

    /** どの商品か。空なら未照合（画面で赤いバッジを出し、選び直させる）。 */
    private Long menuItemId;

    @Valid
    private List<RecipeImportLineForm> lines = new ArrayList<>();

    /** 手入力で開くときの 1 枚。空行だけ並べる。 */
    public static RecipeImportItemForm manual() {
        RecipeImportItemForm item = new RecipeImportItemForm();
        for (int i = 0; i < SPARE_LINES; i++) {
            item.lines.add(new RecipeImportLineForm());
        }
        return item;
    }

    /**
     * このカードごと捨ててよいか（商品も材料も何も入っていない）。
     *
     * <p>「✕ この品を外す」を押さなくても、空のカードは保存で無視されます。
     */
    public boolean isBlank() {
        return (readName == null || readName.isBlank())
                && menuItemId == null
                && filledLines().isEmpty();
    }

    /** 書かれた材料行だけ（予備の空行を除く）。 */
    public List<RecipeImportLineForm> filledLines() {
        List<RecipeImportLineForm> result = new ArrayList<>();
        for (RecipeImportLineForm line : lines) {
            if (!line.isBlank()) {
                result.add(line);
            }
        }
        return result;
    }

    /**
     * 商品が照合できていないか。画面の赤いバッジ「商品が見つかりません」の目印。
     *
     * <p>空のカードは光らせません（直すものが無いため）。
     */
    public boolean isUnmatched() {
        return !isBlank() && menuItemId == null;
    }

    /** 登録できる状態か。商品が決まっていて、材料が 1 行以上ある。 */
    public boolean isReady() {
        return menuItemId != null && !filledLines().isEmpty();
    }

    public String getReadName() {
        return readName;
    }

    public void setReadName(String readName) {
        this.readName = readName;
    }

    public Long getMenuItemId() {
        return menuItemId;
    }

    public void setMenuItemId(Long menuItemId) {
        this.menuItemId = menuItemId;
    }

    public List<RecipeImportLineForm> getLines() {
        return lines;
    }

    public void setLines(List<RecipeImportLineForm> lines) {
        this.lines = lines;
    }
}
