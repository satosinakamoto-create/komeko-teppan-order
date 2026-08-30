package jp.komeko.order.inventory.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * レシートの品名と食材のつなぎ目。<b>「1 回教えたら、次から自動」の記憶そのもの</b>。
 *
 * <p><b>これが要る理由</b><br>
 * スーパーのレシートには「エリンギ 120」としか書いてありません。
 * 何グラムなのかはどこにも印字されていない。
 * 人は袋を見れば 100g だと分かりますが、システムには分かりません。
 *
 * <p>そこで<b>1 回だけ教えてもらう</b>ことにしました。
 * 「このレシートの『エリンギ』は、食材『エリンギ』の 100g だよ」と一度登録すれば、
 * 次に同じ品名が出てきたときは自動で 100g として在庫に積まれます。
 * 商品の写真を撮って内容量を AI に読ませる道も用意していますが、
 * 本命はこちらです。<b>毎回やる作業は続かないが、1 回だけなら続く</b>からです。
 *
 * <p><b>{@link #qtyPerUnit} が null のとき＝まだ教わっていない</b><br>
 * この状態でも仕入れは保存できます（お金の記録としては完全なので）。
 * ただし在庫には積まれないので、確認画面で黄色く表示して
 * 「教えてくれれば在庫にも入ります」と促します。
 * <b>入力を止めない</b>のが大事で、忙しい日に手が止まるほうが害が大きい。
 *
 * <p><b>「量が分からない」に決着をつける逃げ道</b><br>
 * どうしてもグラム数が分からない食材は、食材側の単位を「パック」にして
 * ここに 1 を入れます。以後その食材はパック単位で数えられ、黄色は出ません。
 * 精度は落ちますが、<b>ずっと黄色が出続けて無視されるようになる</b>よりましです。
 */
@Entity
@Table(name = "item_alias", indexes = {
        @Index(name = "idx_item_alias_text", columnList = "alias_text", unique = true)
})
public class ItemAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 正規化したレシート記載名。必ず {@link AliasText#normalize} を通した値。
     *
     * <p>生の文字列で持つと「ｷｬﾍﾞﾂ」と「キャベツ」が別行になり、
     * 同じ食材に何度も教え直すはめになります。
     */
    @Column(name = "alias_text", nullable = false, unique = true, length = 120)
    private String aliasText;

    /** 表示用に取っておく、教わったときの生の品名。 */
    @Column(name = "sample_text", length = 120)
    private String sampleText;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    /**
     * レシート 1 個あたり、食材の単位で何個ぶんか。null なら未学習。
     *
     * <p>例: レシートの「エリンギ」1 個 = 食材エリンギ（単位 g）の 100 → {@code 100}
     */
    @Column(name = "qty_per_unit", precision = 12, scale = 3)
    private BigDecimal qtyPerUnit;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected ItemAlias() {
        // JPA 用
    }

    public ItemAlias(String aliasText, String sampleText, Ingredient ingredient,
                     BigDecimal qtyPerUnit, LocalDateTime updatedAt) {
        this.aliasText = aliasText;
        this.sampleText = sampleText;
        this.ingredient = ingredient;
        this.qtyPerUnit = qtyPerUnit;
        this.updatedAt = updatedAt;
    }

    /** 内容量まで教わっているか。false なら在庫に積めない（＝画面で黄色）。 */
    public boolean isLearned() {
        return qtyPerUnit != null && qtyPerUnit.signum() > 0;
    }

    /**
     * レシートの個数から、在庫に積む量を出す。
     *
     * @param receiptQuantity レシート上の個数。null なら 1 個として扱う
     * @return 在庫に積む量。未学習なら null
     */
    public BigDecimal toStockQty(BigDecimal receiptQuantity) {
        if (!isLearned()) {
            return null;
        }
        BigDecimal count = receiptQuantity != null ? receiptQuantity : BigDecimal.ONE;
        return qtyPerUnit.multiply(count);
    }

    // ── getter / setter ──

    public Long getId() {
        return id;
    }

    public String getAliasText() {
        return aliasText;
    }

    public String getSampleText() {
        return sampleText;
    }

    public void setSampleText(String sampleText) {
        this.sampleText = sampleText;
    }

    public Ingredient getIngredient() {
        return ingredient;
    }

    public void setIngredient(Ingredient ingredient) {
        this.ingredient = ingredient;
    }

    public BigDecimal getQtyPerUnit() {
        return qtyPerUnit;
    }

    public void setQtyPerUnit(BigDecimal qtyPerUnit) {
        this.qtyPerUnit = qtyPerUnit;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
