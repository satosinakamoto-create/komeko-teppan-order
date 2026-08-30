package jp.komeko.order.inventory.web.form;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jp.komeko.order.inventory.domain.StocktakeReason;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.NumberFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 棚卸し・廃棄の入力フォーム。
 *
 * <p><b>「棚卸し」と「廃棄」で同じフォームを使います。</b>
 * 送り先の URL が違うだけで、入力してもらう項目は同じだからです。
 * 分けると同じ入力欄を 2 つ書くことになり、片方だけ直す事故が起きます。
 *
 * <p><b>数量は必ず正の数で入力してもらいます。</b>
 * 減らすときの符号はコントローラで付けます。
 * マイナスを人に入力させると、付け忘れて逆に増えるという事故が必ず起きます。
 */
public class StocktakeForm {

    @NotNull(message = "食材を選んでください")
    private Long ingredientId;

    @NotNull(message = "日付を入力してください")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate takenOn;

    @NotNull(message = "数量を入力してください")
    @NumberFormat(pattern = "#.###")
    private BigDecimal quantity;

    /** 減らす理由。棚卸しのときは使わない。 */
    private StocktakeReason reason = StocktakeReason.WASTE;

    @Size(max = 200, message = "メモは200文字以内で入力してください")
    private String memo;

    public StocktakeForm() {
        // フォームバインド用
    }

    public StocktakeForm(LocalDate today) {
        this.takenOn = today;
    }

    public Long getIngredientId() {
        return ingredientId;
    }

    public void setIngredientId(Long ingredientId) {
        this.ingredientId = ingredientId;
    }

    public LocalDate getTakenOn() {
        return takenOn;
    }

    public void setTakenOn(LocalDate takenOn) {
        this.takenOn = takenOn;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public StocktakeReason getReason() {
        return reason;
    }

    public void setReason(StocktakeReason reason) {
        this.reason = reason;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }
}
