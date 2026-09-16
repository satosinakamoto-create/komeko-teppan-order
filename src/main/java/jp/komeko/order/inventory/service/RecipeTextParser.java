package jp.komeko.order.inventory.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 貼り付けたテキストからレシピを読む（設計 ト09d 842:10569）。
 *
 * <p>Figma のカードにこう書いてあります——
 * 「スマホのメモや LINE に書いたレシピを、そのまま貼るだけ」
 * 「1 行に『材料 分量』で書いてあれば読めます」。
 *
 * <p><b>AI を使いません。</b>だから {@code ANTHROPIC_API_KEY} が無くても動き、
 * 課金もゼロで、通信も発生しません。読み取りの精度は AI に劣りますが、
 * この画面の約束は<b>「読んだまま保存しない」</b>——必ず確認画面を通るので、
 * 甘い読み取りが害になりません。
 *
 * <p><b>商品名と材料の見分け方</b>
 * <pre>
 *   分量（数字）で終わる行 … 材料
 *   それ以外の行           … 新しい商品の名前
 *   空行                   … 区切り（次の行は商品名として読む）
 * </pre>
 * ノートやメモの書き方として自然な形を選びました。
 * 外しても確認画面で直せるので、<b>迷ったら拾う側に倒します</b>。
 *
 * <p><b>読めない行を捨てません。</b>「ひとつかみ」「適量」のような書き方は
 * 現実にあります。黙って捨てると、人は<b>ノートにあった材料が消えたことに
 * 気づけません</b>。材料名だけの行として残し、分量は確認画面で入れてもらいます。
 */
@Service
public class RecipeTextParser {

    /**
     * 行末の「数字＋単位」を取る。
     *
     * <p>単位は取りません。<b>単位は食材マスタが持っている</b>ので、
     * ここで読んだ「g」「個」を持ち回ると、食材の単位と食い違ったときに
     * どちらが正しいか決められなくなります。数字だけ取り、
     * 単位は照合した食材のものを使います。
     *
     * <p><b>数字の前に区切り（空白・コロン）を必ず要求します。</b>
     * 要求しないと、数字で終わる<b>商品名</b>が材料として読まれます。
     * 「たこ焼き-2026」「ハイボール 2杯」のような品名は現実にあり、
     * 商品名が材料の行に化けると、その品まるごとが登録対象から外れます。
     * 代わりに「キャベツ150g」（区切りなし）は読めなくなりますが、
     * <b>読めない行は材料名として残る</b>ので、確認画面で分量を入れれば済みます。
     * 消えるより残るほうがまし、という判断です。
     */
    private static final Pattern TRAILING_QUANTITY =
            Pattern.compile("^(.*?)[\\s:：]+([0-9]+(?:\\.[0-9]+)?)\\s*[^0-9\\s]{0,4}$");

    /** 行頭の箇条書き記号。「・」「-」「*」「●」など。 */
    private static final Pattern BULLET = Pattern.compile("^[\\s・\\-*●○◦‣▪>＞]+");

    /** 読み取った 1 品。 */
    public record ParsedItem(String name, List<ParsedLine> lines) {
    }

    /** 読み取った材料 1 行。{@code quantity} は読めなければ null。 */
    public record ParsedLine(String name, BigDecimal quantity) {
    }

    /**
     * テキストを品ごとに読む。
     *
     * @param text 貼り付けられたそのままの文字列。null でもよい
     * @return 材料が 1 行以上ある品だけ。商品名を書き忘れていれば {@code name} は null
     */
    public List<ParsedItem> parse(String text) {
        List<ParsedItem> items = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return items;
        }

        // ★ 空行で「かたまり」に割り、かたまりごとに 1 品として読む。
        //
        //   かたまりの 1 行目に分量が無ければ  → それが商品名、残りが材料
        //   かたまりの 1 行目から分量があれば  → 商品名なし、全部が材料
        //
        //   「行ごとに商品名か材料かを判定する」形だと、分量が読めない材料
        //   （「紅しょうが ひとつかみ」）が商品名と誤読され、
        //   材料ゼロの品として黙って消えます。実際に最初その作りで落としました。
        //   かたまりで見れば、読めない行も 2 行目以降なら必ず材料として残ります。
        //
        //   ★ 代わりに諦めたこと: 空行を入れずに 2 品続けて書かれた場合、
        //     1 品として読みます。2 品目の名前は材料の行として残るので、
        //     確認画面で気づいて直せます。<b>消えるよりは混ざるほうがまし</b>、
        //     という判断です（消えたことには誰も気づけない）。
        List<String> block = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = stripBullet(raw).trim();
            if (line.isEmpty()) {
                readBlock(items, block);
                block = new ArrayList<>();
                continue;
            }
            block.add(line);
        }
        readBlock(items, block);
        return items;
    }

    /** かたまり 1 つを 1 品として読む。材料が 1 行も無ければ品として出さない。 */
    private void readBlock(List<ParsedItem> items, List<String> block) {
        if (block.isEmpty()) {
            return;
        }
        String name = null;
        int from = 0;
        if (readLine(block.get(0)) == null) {
            // 1 行目に分量が無い ＝ 商品名
            name = block.get(0);
            from = 1;
        }

        List<ParsedLine> lines = new ArrayList<>();
        for (int i = from; i < block.size(); i++) {
            String line = block.get(i);
            ParsedLine parsed = readLine(line);
            // 読めない行も材料名だけ残す。捨てると、ノートにあった材料が
            // 消えたことに人が気づけない。
            lines.add(parsed != null ? parsed : new ParsedLine(line, null));
        }
        flush(items, name, lines);
    }

    /** 材料が 1 行以上あるときだけ 1 品として確定する。 */
    private void flush(List<ParsedItem> items, String name, List<ParsedLine> lines) {
        if (!lines.isEmpty()) {
            items.add(new ParsedItem(name, List.copyOf(lines)));
        }
    }

    private String stripBullet(String raw) {
        return BULLET.matcher(raw == null ? "" : raw).replaceFirst("");
    }

    /**
     * 1 行を「材料 分量」として読む。読めなければ null（＝商品名の候補）。
     *
     * <p>全角の数字を半角に正規化してから見ます。ノートをそのまま打った人の
     * 「１５０ｇ」が読めないと、<b>読めた行と読めない行が書き方だけで分かれて</b>
     * しまい、理由が人に分かりません。
     */
    private ParsedLine readLine(String line) {
        String normalized = Normalizer.normalize(line, Normalizer.Form.NFKC);
        Matcher m = TRAILING_QUANTITY.matcher(normalized);
        if (!m.matches()) {
            return null;
        }
        String name = m.group(1).trim();
        if (name.isEmpty()) {
            // 「150g」だけの行。材料名が無いので商品名でも材料でもない。
            // 落とさず、そのまま材料名として残して人に直してもらう。
            return new ParsedLine(line.trim(), null);
        }
        try {
            return new ParsedLine(name, new BigDecimal(m.group(2)));
        } catch (NumberFormatException e) {
            return new ParsedLine(line.trim(), null);
        }
    }
}
