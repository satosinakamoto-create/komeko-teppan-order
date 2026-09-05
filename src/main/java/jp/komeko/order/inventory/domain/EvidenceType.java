package jp.komeko.order.inventory.domain;

/**
 * そのレシートが、消費税の仕入税額控除の証憑としてどういう扱いになるか。
 *
 * <p><b>前提（インボイス制度）</b><br>
 * 消費税は「売上で預かった税」から「仕入れで払った税」を引いて納めます。
 * この「引く」を仕入税額控除といい、原則として
 * <b>適格請求書発行事業者が出した証憑</b>がないと引けません。
 * 飲食店が受け取るレシートは、記載事項を満たしていれば
 * {@link #SIMPLIFIED_INVOICE 適格簡易請求書} として認められます。
 *
 * <p><b>引けない相手からの仕入れもゼロにはならない</b><br>
 * 登録していない事業者（免税事業者）からの仕入れにも、
 * 何割かは引いてよいという経過措置があります（{@link DeductionRatePeriod}）。
 * その割合は年々下がっていくので、率そのものは日付から引きます。
 */
public enum EvidenceType {

    /**
     * 適格簡易請求書。小売・飲食・タクシーなど不特定多数を相手にする業種が出せる、
     * 宛名の要らないレシート。買い出しレシートの大半はこれ。
     */
    SIMPLIFIED_INVOICE("適格簡易請求書", true),

    /** 適格請求書（宛名のある通常のインボイス）。卸からの請求書など。 */
    QUALIFIED_INVOICE("適格請求書", true),

    /**
     * 登録番号のない証憑。免税事業者からの仕入れなど。
     * 経過措置の控除率だけが適用される。
     */
    NOT_QUALIFIED("インボイスなし", false),

    /**
     * 帳簿のみで控除できる特例。
     *
     * <p>税込 1 万円未満の課税仕入れは、証憑がなくても帳簿の記載だけで控除できます
     * （少額特例、2029-09-30 まで）。ただし<b>使えるのは基準期間の課税売上高が
     * 1 億円以下などの事業者に限られる</b>点に注意。
     */
    BOOK_ONLY_SPECIAL("帳簿のみ（少額特例）", true);

    private final String label;
    private final boolean fullyDeductible;

    EvidenceType(String label, boolean fullyDeductible) {
        this.label = label;
        this.fullyDeductible = fullyDeductible;
    }

    public String getLabel() {
        return label;
    }

    /** 経過措置ではなく全額控除できる区分か。 */
    public boolean isFullyDeductible() {
        return fullyDeductible;
    }
}
