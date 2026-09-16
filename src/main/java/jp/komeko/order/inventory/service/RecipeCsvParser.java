package jp.komeko.order.inventory.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV からレシピを読む（設計 ト09d 842:10558）。
 *
 * <p>Figma のカードにこう書いてあります——
 * 「前職のエクセルをそのまま。<b>AI を通さないので、いちばん正確です</b>」
 * 「列は 商品名・材料・分量 があれば OK」。
 *
 * <p><b>AI を使いません。</b>だから読み間違いが起きず、キーも課金も要りません。
 * 3 つの入口のうち、これがいちばん確実です。
 *
 * <p><b>列の順番を決め打ちしません。</b>前職のエクセルがどんな列構成かは
 * こちらで決められないので、見出し行から「商品名・材料・分量にあたる列」を探します。
 * 余分な列（売価・原価など）は無視します。
 *
 * <p><b>見つからなければ黙って 0 件を返さず、理由を言って止まります。</b>
 * 「取り込んだのに何も出てこない」がいちばん困るためです。
 */
@Service
public class RecipeCsvParser {

    /** 商品名の列に使われうる見出し。前から順に探す。 */
    private static final List<String> DISH_HEADERS =
            List.of("商品名", "商品", "品名", "メニュー", "メニュー名", "料理名", "item", "menu");

    /** 材料の列に使われうる見出し。 */
    private static final List<String> INGREDIENT_HEADERS =
            List.of("材料", "材料名", "食材", "食材名", "原材料", "ingredient");

    /** 分量の列に使われうる見出し。 */
    private static final List<String> QUANTITY_HEADERS =
            List.of("分量", "量", "使用量", "数量", "qty", "quantity", "amount");

    /**
     * CSV（またはタブ区切り）を品ごとに読む。
     *
     * @param text 貼り付けられた、あるいは読み込んだ中身
     * @return 材料が 1 行以上ある品だけ
     * @throws IllegalArgumentException 商品名・材料・分量の列が見つからないとき
     */
    public List<RecipeTextParser.ParsedItem> parse(String text) {
        List<RecipeTextParser.ParsedItem> items = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return items;
        }

        List<String> rows = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            if (!raw.isBlank()) {
                rows.add(raw);
            }
        }
        if (rows.isEmpty()) {
            return items;
        }

        // 区切りはカンマかタブ。エクセルから直に貼るとタブになる。
        char delimiter = rows.get(0).indexOf('\t') >= 0 ? '\t' : ',';

        List<String> header = splitRow(rows.get(0), delimiter);
        int dishAt = findColumn(header, DISH_HEADERS);
        int ingredientAt = findColumn(header, INGREDIENT_HEADERS);
        int quantityAt = findColumn(header, QUANTITY_HEADERS);

        List<String> missing = new ArrayList<>();
        if (dishAt < 0) {
            missing.add("商品名");
        }
        if (ingredientAt < 0) {
            missing.add("材料");
        }
        if (quantityAt < 0) {
            missing.add("分量");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "1 行目の見出しに " + String.join("・", missing)
                    + " の列が見つかりませんでした。1 行目を「商品名,材料,分量」にしてください");
        }

        String currentDish = null;
        List<RecipeTextParser.ParsedLine> currentLines = new ArrayList<>();

        for (int i = 1; i < rows.size(); i++) {
            List<String> cells = splitRow(rows.get(i), delimiter);
            String dish = cellAt(cells, dishAt);
            String ingredient = cellAt(cells, ingredientAt);
            String quantity = cellAt(cells, quantityAt);

            if (ingredient.isBlank()) {
                continue;   // 材料が無ければレシピ行にならない
            }

            // ★ 商品名が空欄なら直前の続き。
            //   エクセルでは 1 品目の行だけ商品名を書いて、以降は空欄にするのが普通。
            if (!dish.isBlank() && !dish.equals(currentDish)) {
                flush(items, currentDish, currentLines);
                currentLines = new ArrayList<>();
                currentDish = dish;
            }
            currentLines.add(new RecipeTextParser.ParsedLine(ingredient, readQuantity(quantity)));
        }
        flush(items, currentDish, currentLines);
        return items;
    }

    private void flush(List<RecipeTextParser.ParsedItem> items, String name,
                       List<RecipeTextParser.ParsedLine> lines) {
        if (!lines.isEmpty()) {
            items.add(new RecipeTextParser.ParsedItem(name, List.copyOf(lines)));
        }
    }

    /**
     * 分量のセルを数にする。読めなければ null。
     *
     * <p><b>読めなくても行は捨てません。</b>「ひとつかみ」と書いてあっても、
     * 材料名は残して確認画面で人に入れてもらいます。
     * 黙って捨てると、エクセルにあった材料が消えたことに気づけません。
     */
    private BigDecimal readQuantity(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("[^0-9.]", "");
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 見出しの言い回しのゆれを吸収して列を探す。大文字小文字・全角半角は無視。 */
    private int findColumn(List<String> header, List<String> candidates) {
        for (int i = 0; i < header.size(); i++) {
            String cell = Normalizer.normalize(header.get(i), Normalizer.Form.NFKC)
                    .trim().toLowerCase();
            for (String candidate : candidates) {
                if (cell.equals(candidate.toLowerCase())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String cellAt(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index).trim() : "";
    }

    /**
     * CSV の 1 行を分ける。引用符の中の区切り文字は分けない。
     *
     * <p>自前で書いているのは、依存を増やさない既存方針に合わせるためです
     * （AI 読取も公式 SDK ではなく RestClient 直叩きにしてあります）。
     * 扱うのは「引用符とその中の区切り・二重引用符」まで。
     * 改行を含むセルには対応しません——手書きの原価表に出てこないためです。
     */
    private List<String> splitRow(String row, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < row.length() && row.charAt(i + 1) == '"') {
                        current.append('"');   // "" は引用符 1 つ
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }
}
