package jp.komeko.order.inventory.domain;

/**
 * 登録番号（T + 13 桁）をどこまで確かめられたか。
 *
 * <p>「確認済み / 未確認」の 2 値にしていないのは、
 * <b>確かめ方に段階があり、どこまで進んだかで意味が変わる</b>からです。
 * 形が合っているだけの番号と、国税庁に実在を確認した番号を
 * 同じ「確認済み」にまとめてしまうと、税理士が見たときに判断できません。
 */
public enum RegistrationVerifyStatus {

    /** 登録番号そのものがない（免税事業者からの仕入れなど）。 */
    NONE("登録番号なし"),

    /** 形は合っているが、まだ何も確かめていない。 */
    UNVERIFIED("未確認"),

    /**
     * 法人番号としての検査用数字に通った。
     * OCR の読み違いはほぼ潰せるが、実在するかまでは分からない。
     */
    CHECK_DIGIT_OK("検算OK"),

    /** 国税庁の公表サイトで実在と有効性を確認した。 */
    VERIFIED("確認済み"),

    /** 国税庁の公表サイトに見つからなかった。 */
    NOT_FOUND("該当なし"),

    /** 登録が取り消されている（取引日時点で有効かは別途判断が要る）。 */
    REVOKED("登録取消");

    private final String label;

    RegistrationVerifyStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 税理士が目を通すべき状態か（例外リストに出す対象）。 */
    public boolean needsAttention() {
        return this == NOT_FOUND || this == REVOKED;
    }
}
