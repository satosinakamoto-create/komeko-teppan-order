package jp.komeko.order.inventory.web.form;

import jp.komeko.order.inventory.domain.IngredientUnit;
import org.springframework.format.annotation.NumberFormat;

import java.math.BigDecimal;

/**
 * 取り込み確認画面の材料 1 行（設計 ト09b 807:1707）。
 *
 * <p>Figma の列と 1 対 1 に対応します。
 * <pre>
 *   読み取った材料 … readName     （AI／手入力が読んだ生の名前。人は直さない）
 *   食材との照合   … ingredientId （ドロップダウン。空なら「食材にありません」）
 *   1品あたりの量  … qtyPerItem   （数値＋単位の記号）
 *   状態           … 画面側で判定（✓一致／＋その場で作成）
 * </pre>
 *
 * <p><b>数値はラッパー型（{@code BigDecimal}）にしています。</b>
 * {@code double} や素の数値だと未入力が 0 になり、「0g の行」と
 * 「まだ書いていない行」が見分けられなくなります（明細行と同じ判断）。
 *
 * <p><b>{@code readName} を人に直させないのはわざとです。</b>
 * これは「ノートにこう書いてあった」という読み取り元の記録で、
 * 直してよいのは<b>照合先</b>（どの食材か）と<b>量</b>です。
 * 元の字を書き換えると、あとで読み間違いを追えなくなります。
 */
public class RecipeImportLineForm {

    /** ノート・エクセルにそう書いてあった材料名。表示専用。 */
    private String readName;

    /** どの食材か。空なら未照合（画面で赤くし、「＋ その場で作成」を出す）。 */
    private Long ingredientId;

    /**
     * 1 品あたりの量。
     *
     * <p>桁はレシピ行の列（{@code recipe_line.qty_per_item} = precision 12 / scale 3）に合わせます。
     * 上限を書いておかないと、13 桁を入れたときに DB の桁あふれで 500 になります。
     */
    @jakarta.validation.constraints.Digits(integer = 9, fraction = 3,
            message = "分量が大きすぎます。整数は 9 桁まで・小数は 3 桁までで入力してください")
    @NumberFormat(pattern = "#.###")
    private BigDecimal qtyPerItem;

    /**
     * 「＋ その場で作成」で食材を作るときの単位。
     *
     * <p><b>これは画面の一時的な入力ではありません。</b>作成した食材の単位として
     * そのまま保存され、あとから変えても過去の数量は換算されません
     * （{@code IngredientService.update} の注意書き）。レシート側の
     * {@code newUnit} が同じ作りで、Javadoc が「保存しません」と書いてあるのに
     * 実際は保存される、という食い違いがありました。ここでは最初から
     * <b>消えない入力</b>として扱います。
     */
    private IngredientUnit newUnit = IngredientUnit.GRAM;

    /**
     * 予備の空行か。
     *
     * <p>確認画面には空行を並べておき、書かれた行だけ保存します。
     * 行を増やすのに JavaScript を使わずに済ませるための割り切りです。
     */
    public boolean isBlank() {
        return (readName == null || readName.isBlank())
                && ingredientId == null
                && qtyPerItem == null;
    }

    /**
     * 照合できていない行か。画面で赤くし、「＋ その場で作成」を出す目印。
     *
     * <p>空行は光らせません。<b>全部光らせると警告そのものが無視される</b>ので、
     * 直すべき行だけに絞ります（レシートの確認画面と同じ判断）。
     */
    public boolean isUnmatched() {
        return !isBlank() && ingredientId == null;
    }

    public String getReadName() {
        return readName;
    }

    public void setReadName(String readName) {
        this.readName = readName;
    }

    public Long getIngredientId() {
        return ingredientId;
    }

    public void setIngredientId(Long ingredientId) {
        this.ingredientId = ingredientId;
    }

    public BigDecimal getQtyPerItem() {
        return qtyPerItem;
    }

    public void setQtyPerItem(BigDecimal qtyPerItem) {
        this.qtyPerItem = qtyPerItem;
    }

    public IngredientUnit getNewUnit() {
        return newUnit;
    }

    public void setNewUnit(IngredientUnit newUnit) {
        this.newUnit = newUnit;
    }
}
