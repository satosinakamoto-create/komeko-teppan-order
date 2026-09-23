package jp.komeko.order.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * その商品の「その他材料費」。レシピに書ききらない材料をまとめた金額。
 *
 * <p><b>何のためにあるか</b><br>
 * ソース・青のり・かつお節のように、1 品あたり何グラムかを測らない材料があります。
 * それらを g 数で登録させると、原価を知りたいだけの人の手が止まります。
 * 「合わせて 40 円」と置ければ先に進めます。
 *
 * <p><b>食材を 1 つも登録しない使い方も正しい使い方です。</b>
 * 「たこ焼きの原価は 180 円」とだけ入れて保存できます。
 * その商品は原価と原価率が出ますが、<b>売れても在庫は減りません</b>
 * （どの食材が減るのか書いていないため）。在庫まで見たい商品だけ
 * レシピに食材を足す、という使い分けになります。
 *
 * <p><b>なぜレシピ行（{@link RecipeLine}）にしなかったか</b><br>
 * 「食材の無いレシピ行」を許すと、{@code RecipeLineRepository} の
 * 内部結合（{@code join fetch r.ingredient}）に引っかかって
 * <b>その行が結果から黙って消えます</b>。原価表からも消費計算からも同時に
 * 落ちるので、「保存したのに出てこない・消せない行」になります。
 * 別の入れ物にすれば、消費計算からは<b>そもそも見えない</b>ので、
 * 除外の処理を書く必要がなく、書き忘れも起きません。
 *
 * <p><b>金額は税込の {@code int}（円）。</b>
 * 価格は税込で保持、金額は必ず int、が既存の規約です。
 * 税抜が要るときは {@code TaxCalculator.netAmount} で割り戻します。
 * 税率はここに持ちません（改正のたびに直す場所が増えるため）。
 *
 * <p><b>0 円は保存しません。</b>
 * 残すと「検討した結果 0 円だった」と「まだ入れていない」が区別できなくなります。
 * 削除した食材の分類で「その他」を既定にしなかったのと同じ理由です。
 */
@Entity
@Table(name = "recipe_other_cost")
public class RecipeOtherCost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * どの商品のものか。
     *
     * <p><b>{@code MenuItem} への関連ではなく id をそのまま持ちます。</b>
     * {@code open-in-view: false} なので、画面を描くときには DB 接続がありません。
     * 関連にすると読み出す場所に気を遣うことになりますが、この行が要るのは
     * 「商品 id から金額を引く」ときだけなので、id で足ります。
     * 参照の正しさは DB の外部キーが守ります。
     */
    @Column(name = "menu_item_id", nullable = false, unique = true)
    private Long menuItemId;

    /** 円・税込。必ず 1 以上（0 と負数は保存しない）。 */
    @Column(name = "amount_including_tax", nullable = false)
    private int amountIncludingTax;

    /** 何をまとめた金額か。「ソース・青のり・かつお節」など。 */
    @Column(length = 100)
    private String memo;

    /** JPA 用。 */
    protected RecipeOtherCost() {
    }

    public RecipeOtherCost(Long menuItemId, int amountIncludingTax, String memo) {
        this.menuItemId = menuItemId;
        setAmountIncludingTax(amountIncludingTax);
        this.memo = memo;
    }

    public Long getId() {
        return id;
    }

    public Long getMenuItemId() {
        return menuItemId;
    }

    public int getAmountIncludingTax() {
        return amountIncludingTax;
    }

    /**
     * 金額を入れる。
     *
     * <p><b>0 以下は受け付けません。</b>負の金額を許すと、その商品の原価から
     * 材料費が引かれて原価率が実際より低く出ます。「思ったより儲かる」という
     * 誤解は、静かに効いてくるぶんだけ質が悪い。
     * オプションの追加料金を 0 円以上に閉じているのと同じ判断です。
     *
     * <p>0 そのものは「入れない」と同じ意味なので、呼び出し側が行ごと消します
     * （{@code RecipeService.setOtherCost}）。ここまで来たら異常です。
     */
    public void setAmountIncludingTax(int amountIncludingTax) {
        if (amountIncludingTax <= 0) {
            throw new IllegalArgumentException(
                    "その他材料費は 1 円以上で入れてください（0 円なら項目ごと消します）");
        }
        this.amountIncludingTax = amountIncludingTax;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }
}
