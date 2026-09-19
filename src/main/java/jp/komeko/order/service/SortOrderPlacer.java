package jp.komeko.order.service;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 並びの中で 1 つを動かして、並び順を 10 きざみで振り直す（2026-09-19）。
 *
 * <p>店主の指示「商品、カテゴリー、卓にもドラッグ＆ドロップ実装してほしい」。
 * 3 つとも並び順の持ち方は同じ（{@code sortOrder} の昇順）なので、
 * 動かし方もここ 1 か所にまとめてあります。
 *
 * <h2>行き先は「どの並びの隣か」で指す</h2>
 * <p>{@code beforeId} があればその直前、無ければ {@code afterId} の直後。
 *
 * <p><b>「相手が null なら先頭」という決め方はしません。</b>
 * 画面は<b>下端に落としたときに送るものが無く</b>（その行の次が無い）、
 * 空を送ります。null を先頭の意味にすると、下端へ落としたものが先頭へ飛びます
 * （2026-09-19 に商品で実際に起きました）。
 *
 * <p>絞り込んでいるときも同じ理由で効きます。見えている最後の行のさらに下に
 * 隠れた行がいることがあり、「いちばん下」と言われても
 * <b>見えている下</b>なのか<b>本当の下</b>なのか決められません。
 * 隣を指せば迷いません。
 *
 * <h2>10 きざみで振り直す</h2>
 * <p>1 ずつずらしていくと、いつか隣同士の数字が同じになって順番が決まらなくなります
 * （そうなると id 順に落ちる）。動かした列は通しで振り直します。
 */
final class SortOrderPlacer {

    private SortOrderPlacer() {
    }

    /**
     * @param rows     いまの並び。<b>この場で並べ替えます</b>（呼ぶ側が可変リストを渡すこと）
     * @param movingId 動かすものの id
     * @param idOf     id の取り出し方
     * @param setOrder 並び順の入れ方
     * @param beforeId この id の直前に入れる
     * @param afterId  {@code beforeId} が無いとき、この id の直後に入れる
     * @return 動かせたら true。相手が見つからない・動かす必要が無いときは false
     */
    static <T> boolean place(List<T> rows, Long movingId,
                             Function<T, Long> idOf,
                             BiConsumer<T, Integer> setOrder,
                             Long beforeId, Long afterId) {
        int from = indexOf(rows, movingId, idOf);
        if (from < 0) {
            return false;
        }

        int to;
        if (beforeId != null) {
            int at = indexOf(rows, beforeId, idOf);
            if (at < 0) {
                // 別の並びのものか、消えたもの。動かさない
                return false;
            }
            to = at;
        } else if (afterId != null) {
            int at = indexOf(rows, afterId, idOf);
            if (at < 0) {
                return false;
            }
            to = at + 1;
        } else {
            // 行き先が分からない。黙って先頭へ動かすより、何もしないほうが安全
            return false;
        }

        T moving = rows.remove(from);
        // ★ 自分を抜いたぶん、後ろへ動かすときは位置が 1 つ手前にずれます。
        //   ここを間違えると 1 つ行きすぎます。
        if (from < to) {
            to--;
        }
        if (from == to) {
            return false;
        }
        rows.add(to, moving);

        for (int i = 0; i < rows.size(); i++) {
            setOrder.accept(rows.get(i), (i + 1) * 10);
        }
        return true;
    }

    private static <T> int indexOf(List<T> rows, Long id, Function<T, Long> idOf) {
        for (int i = 0; i < rows.size(); i++) {
            if (id.equals(idOf.apply(rows.get(i)))) {
                return i;
            }
        }
        return -1;
    }
}
