package jp.komeko.order.inventory.web.form;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jp.komeko.order.inventory.domain.EvidenceType;
import jp.komeko.order.inventory.domain.PaymentMethod;
import jp.komeko.order.inventory.domain.PurchaseCategory;
import jp.komeko.order.inventory.service.ReceiptReading;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * レシート確認画面のフォーム。
 *
 * <p><b>エンティティを直接フォームにしない</b>という既存の規約に従っています。
 * 画面から来る値は「未入力かもしれない」「文字列かもしれない」ので、
 * エンティティに直接ぶつけると、検証前の壊れた値が業務のオブジェクトに入ってしまいます。
 *
 * <p><b>画像とAI応答を hidden で運ぶ</b><br>
 * 画像は読み取りの時点で保存済みなので、確認画面では
 * そのパスだけを持ち回ります。保存のたびに送り直すより速く、
 * 「確認中にブラウザを閉じた」場合でも画像だけは残ります。
 */
public class PurchaseForm {

    /** 予備の空行の数。行の追加に JavaScript を使わずに済ませるための割り切り。 */
    public static final int SPARE_LINES = 3;

    /** 手入力で始めるときに並べる行数。 */
    public static final int MANUAL_LINES = 8;

    @NotNull(message = "取引日を入力してください")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate purchasedOn;

    /** 受領日。空なら取引日と同じ扱いにする。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate receivedOn;

    @NotBlank(message = "店名を入力してください")
    @Size(max = 120, message = "店名は120文字以内で入力してください")
    private String storeName;

    @NotNull(message = "合計金額を入力してください")
    @Min(value = 0, message = "合計金額は0円以上で入力してください")
    private Integer totalAmount;

    private PaymentMethod paymentMethod = PaymentMethod.CASH;

    @Size(max = 60, message = "登録番号は60文字以内で入力してください")
    private String registrationNumber;

    private EvidenceType evidenceType;

    @Size(max = 300, message = "メモは300文字以内で入力してください")
    private String memo;

    /** 「紙と見比べました」のチェック。ここが入って初めて原本を捨ててよい。 */
    private boolean equivalenceChecked;

    private String imagePath;

    private String ocrJson;

    @Valid
    private List<PurchaseLineForm> lines = new ArrayList<>();

    /** 手入力で始めるための空のフォーム。 */
    public static PurchaseForm manual(LocalDate today) {
        PurchaseForm form = new PurchaseForm();
        form.purchasedOn = today;
        for (int i = 0; i < MANUAL_LINES; i++) {
            form.lines.add(new PurchaseLineForm());
        }
        return form;
    }

    /**
     * AI の読み取り結果からフォームを組み立てる。
     *
     * <p>読めなかった項目はそのまま空欄で残します。
     * それらしい値で埋めると、人は確認せずに保存してしまいます。
     *
     * @param reading      読み取り結果
     * @param imagePath    保存済みの画像パス
     * @param today        既定の取引日（読み取れなかったときに使う）
     * @param defaultRates 税率が読めなかった行に当てる既定値（標準税率）
     */
    public static PurchaseForm fromReading(ReceiptReading reading, String imagePath,
                                           LocalDate today, int defaultRates) {
        PurchaseForm form = new PurchaseForm();
        form.imagePath = imagePath;
        form.ocrJson = reading.rawJson();
        form.storeName = reading.storeName();
        form.totalAmount = reading.totalAmount();
        form.registrationNumber = reading.registrationNumber();
        form.purchasedOn = parseDate(reading.purchasedOn(), today);

        for (ReceiptReading.Line line : reading.lines()) {
            form.lines.add(new PurchaseLineForm(
                    line.itemText(),
                    line.quantity(),
                    line.amount(),
                    line.taxRatePercent() != null ? line.taxRatePercent() : defaultRates,
                    // 軽減税率の印が付いていれば食材の可能性が高い、という程度の当たりを付ける。
                    // あくまで初期値で、人が確認画面で直せる。
                    line.reducedMark() ? PurchaseCategory.FOOD : PurchaseCategory.SUPPLIES));
        }
        for (int i = 0; i < SPARE_LINES; i++) {
            form.lines.add(new PurchaseLineForm());
        }
        return form;
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            // 和暦のまま返ってきた、桁が足りない等。人に直してもらう。
            return fallback;
        }
    }

    /** 入力された行だけ（予備の空行を除く）。 */
    public List<PurchaseLineForm> filledLines() {
        List<PurchaseLineForm> result = new ArrayList<>();
        for (PurchaseLineForm line : lines) {
            if (!line.isBlank()) {
                result.add(line);
            }
        }
        return result;
    }

    /** 明細を足し上げた額（税込）。確認画面で合計とのずれを見せるのに使う。 */
    public int lineTotal() {
        int sum = 0;
        for (PurchaseLineForm line : filledLines()) {
            if (line.getAmount() != null) {
                sum += line.getAmount();
            }
        }
        return sum;
    }

    /** レシートの合計と明細の合算のずれ（0 なら一致）。 */
    public int lineTotalMismatch() {
        return (totalAmount == null ? 0 : totalAmount) - lineTotal();
    }

    // ── getter / setter ──

    public LocalDate getPurchasedOn() {
        return purchasedOn;
    }

    public void setPurchasedOn(LocalDate purchasedOn) {
        this.purchasedOn = purchasedOn;
    }

    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    public void setReceivedOn(LocalDate receivedOn) {
        this.receivedOn = receivedOn;
    }

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public Integer getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(Integer totalAmount) {
        this.totalAmount = totalAmount;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public void setRegistrationNumber(String registrationNumber) {
        this.registrationNumber = registrationNumber;
    }

    public EvidenceType getEvidenceType() {
        return evidenceType;
    }

    public void setEvidenceType(EvidenceType evidenceType) {
        this.evidenceType = evidenceType;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public boolean isEquivalenceChecked() {
        return equivalenceChecked;
    }

    public void setEquivalenceChecked(boolean equivalenceChecked) {
        this.equivalenceChecked = equivalenceChecked;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public String getOcrJson() {
        return ocrJson;
    }

    public void setOcrJson(String ocrJson) {
        this.ocrJson = ocrJson;
    }

    public List<PurchaseLineForm> getLines() {
        return lines;
    }

    public void setLines(List<PurchaseLineForm> lines) {
        this.lines = lines;
    }
}
