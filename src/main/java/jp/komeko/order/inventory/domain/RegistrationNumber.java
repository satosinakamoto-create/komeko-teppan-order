package jp.komeko.order.inventory.domain;

import java.util.Locale;

/**
 * インボイスの登録番号（T + 13 桁）を正規化・検査するユーティリティ。
 *
 * <p><b>3 段階で確かめる</b>
 * <ol>
 *   <li>形が合っているか（{@code T} + 数字 13 桁）… このクラス</li>
 *   <li>検査用数字が合っているか（法人番号のときだけ可能）… このクラス</li>
 *   <li>実在して有効か … 国税庁の公表サイト Web-API に問い合わせる（未実装。
 *       利用にはアプリケーション ID の申請が要るため、取得後に足す）</li>
 * </ol>
 *
 * <p><b>検査用数字で「不合格」でも弾いてはいけない</b><br>
 * 登録番号は、法人なら「T + 法人番号」なので国税庁が公開している計算式で検算できます。
 * ところが<b>個人事業者の 13 桁にはこの計算式が当てはまりません</b>
 * （法人番号とは別体系で、検算方法は公開されていない）。
 * だから検算に通れば「法人番号として妥当」という強い手がかりになりますが、
 * 通らなかったからといって偽物とは限りません。OCR の読み間違いかもしれないし、
 * 個人事業者の番号かもしれない。<b>合格は加点、不合格は保留</b>として扱います。
 */
public final class RegistrationNumber {

    private RegistrationNumber() {
        // ユーティリティクラスなのでインスタンス化させない
    }

    /**
     * OCR が読んだ生の文字列を、比較できる形（{@code T1234567890123}）にそろえる。
     *
     * <p>レシートの印字は「T-1234-5678-9012-3」のようにハイフンや空白が入ることがあり、
     * 全角で印字されることもあります。整形して初めて突き合わせができます。
     *
     * @param raw OCR が読んだ文字列。null 可
     * @return 正規化した登録番号。形が合わなければ null
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : raw.toCharArray()) {
            char n = toHalfWidth(c);
            if (n >= '0' && n <= '9') {
                digits.append(n);
            }
            // T・ハイフン・空白・その他の記号は読み飛ばす
        }
        if (digits.length() != 13) {
            return null;
        }
        // 先頭の「T」が読めていなくても、13 桁そろっていれば登録番号として組み立てる。
        // 感熱紙の T はかすれやすく、そこで弾くと実用にならないため。
        return "T" + digits;
    }

    /** 正規化済みの文字列が {@code T} + 13 桁の形になっているか。 */
    public static boolean hasValidFormat(String normalized) {
        if (normalized == null || normalized.length() != 14) {
            return false;
        }
        if (normalized.charAt(0) != 'T') {
            return false;
        }
        for (int i = 1; i < 14; i++) {
            char c = normalized.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 法人番号としての検査用数字が合っているかを確かめる。
     *
     * <p>国税庁が公開している計算式:
     * <pre>
     *   検査用数字 = 9 −(Σ(n=1..12) Pn × Qn を 9 で割った余り)
     *     Pn : 検査用数字を除いた 12 桁を下 1 桁目から順に P1, P2, … P12
     *     Qn : n が奇数なら 1、偶数なら 2
     * </pre>
     *
     * <p>例として国税庁自身の法人番号 {@code 7000012050002} は、
     * 先頭の 7 が検査用数字で、残り 12 桁から同じ 7 が求まります。
     *
     * @param normalized {@link #normalize} を通した文字列
     * @return 検算に通れば true。形が違う場合や個人事業者の番号は false
     */
    public static boolean matchesCorporateCheckDigit(String normalized) {
        if (!hasValidFormat(normalized)) {
            return false;
        }
        String digits = normalized.substring(1);          // 先頭の T を落とす
        int checkDigit = digits.charAt(0) - '0';          // 13 桁のうち先頭が検査用数字
        String body = digits.substring(1);                // 残り 12 桁

        int sum = 0;
        // body の末尾から数えて n 桁目が Pn。n が奇数なら 1 倍、偶数なら 2 倍。
        for (int n = 1; n <= 12; n++) {
            int p = body.charAt(body.length() - n) - '0';
            sum += p * (n % 2 == 1 ? 1 : 2);
        }
        int expected = 9 - (sum % 9);
        // 余りが 0 のとき expected は 9 になるが、検査用数字として 9 は正しい値
        return expected == checkDigit;
    }

    /** 全角数字を半角に直す（それ以外はそのまま返す）。 */
    private static char toHalfWidth(char c) {
        if (c >= '０' && c <= '９') {
            return (char) ('0' + (c - '０'));
        }
        return c;
    }

    /** 画面に出すための整形（{@code T1234-5678-9012-3} のように区切る）。 */
    public static String forDisplay(String normalized) {
        if (!hasValidFormat(normalized)) {
            return normalized;
        }
        String d = normalized.substring(1);
        return String.format(Locale.ROOT, "T%s-%s-%s-%s",
                d.substring(0, 4), d.substring(4, 8), d.substring(8, 12), d.substring(12));
    }
}
