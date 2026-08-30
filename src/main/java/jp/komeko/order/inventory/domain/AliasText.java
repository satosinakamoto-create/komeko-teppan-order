package jp.komeko.order.inventory.domain;

import java.text.Normalizer;

/**
 * レシートに印字された品名を、名寄せ用の形に整える。
 *
 * <p><b>この小さな関数が「1 回教えたら次から自動」の土台です。</b><br>
 * レシートの品名は同じ商品でも毎回同じ文字列とは限りません。
 * 半角カナだったり、全角の数字が混じったり、末尾に軽減税率の「※」が付いたり。
 * そのまま突き合わせると、同じエリンギが 3 種類の別物として並びます。
 *
 * <p>だから<b>保存するときも探すときも、必ずここを通した形</b>を使います。
 * ここが正規化の唯一の入口です。
 *
 * <p><b>やること</b>
 * <ol>
 *   <li>NFKC 正規化 … 半角カナ「ｷｬﾍﾞﾂ」→「キャベツ」、全角英数「１２３」→「123」</li>
 *   <li>小文字化 … 「PB」と「pb」を同じ扱いに</li>
 *   <li>記号・空白の除去 … 「※」「＊」「・」やスペースは店ごとの飾りで、品物の違いではない</li>
 * </ol>
 *
 * <p><b>やらないこと</b><br>
 * 数字は消しません。「牛乳500」と「牛乳1000」は別の商品だからです。
 * 「値引」「割引」の行も消しません。それは名寄せではなく明細の意味の話で、
 * ここで判断すると人が画面で直せなくなります。
 *
 * <p>元の文字列は {@link PurchaseLine#getItemText()} に生のまま残ります。
 * 整形するのは<b>照合のときだけ</b>で、記録は生に近いほうが強い、が方針です。
 */
public final class AliasText {

    /** 名寄せのじゃまになる飾り。品物の違いを表さない文字。 */
    private static final String NOISE_CHARS = "※*＊・･,，、.。/／\\-ー―‐_＿()（）[]［］{}｛｝\"'`|｜:：;；";

    private AliasText() {
        // ユーティリティクラス
    }

    /**
     * 名寄せ用に整える。
     *
     * @param raw レシートに印字されていた品名
     * @return 整えた文字列。空になる場合は null（照合の対象にしない）
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String work = Normalizer.normalize(raw, Normalizer.Form.NFKC);

        StringBuilder sb = new StringBuilder(work.length());
        for (int i = 0; i < work.length(); i++) {
            char c = work.charAt(i);
            if (Character.isWhitespace(c) || NOISE_CHARS.indexOf(c) >= 0) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }

        String result = sb.toString();
        return result.isEmpty() ? null : result;
    }

    /** 2 つの品名が同じものを指しているか。 */
    public static boolean sameItem(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        return na != null && na.equals(nb);
    }
}
