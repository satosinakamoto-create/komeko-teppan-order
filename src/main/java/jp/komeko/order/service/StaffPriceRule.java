package jp.komeko.order.service;

import jp.komeko.order.domain.MenuItem;

/**
 * 店員が付けた金額を確かめる、ただ 1 つの判断役。
 *
 * <p><b>なぜクラスとして切り出すのか</b><br>
 * 金額を入れる場面が 2 つあるためです。
 *
 * <ul>
 *   <li>{@code OrderService#placeByStaff} … 伝票の画面から 1 品ずつ入れる</li>
 *   <li>{@code CartService#addByStaff} … 店舗版スマホでカートに積む</li>
 * </ul>
 *
 * <p>同じ判断を 2 か所に書くと、片方だけ緩めたときに<b>そちらから素通り</b>します。
 * 通ってしまえば結果は同じ「請求額が違う」なので、
 * どちらの口から入ったかは事故の大きさに関係ありません。
 *
 * <p><b>ここが本当の門です。</b>画面の {@code required} は近道でしかありません
 * （開発者ツールから外せます）。
 */
public final class StaffPriceRule {

    /**
     * 1 品あたりの上限（円・税込）。
     *
     * <p>正しい値を決めるためのものではなく、<b>桁の打ち間違いを止める</b>ためのものです。
     * この店のメニューで 1 品 10 万円に届くものはありません。
     * 「0 を 1 つ余分に打った」の大半はここで止まります
     * （6,800 → 68,000 のような間違いは通ります。それは人が読んで気づく領域です）。
     */
    public static final int LIMIT = 100_000;

    private StaffPriceRule() {
    }

    /** 時価の品か（価格 0 以下）。 */
    public static boolean isMarketPriced(MenuItem item) {
        return item.getPrice() <= 0;
    }

    /**
     * その品に使う単価を決める。
     *
     * <p>時価なら店員が入れた金額、そうでなければマスタの価格です。
     *
     * @param item         対象の品
     * @param decidedPrice 店員が入れた金額（入力が無ければ null）
     * @throws OrderRejectedException 時価なのに金額が無い／大きすぎる、
     *                                時価でないのに金額が付いてきた
     */
    public static int unitPriceOf(MenuItem item, Integer decidedPrice) {
        return isMarketPriced(item) ? required(item, decidedPrice) : rejected(item, decidedPrice);
    }

    /**
     * 時価の品に付けた金額を確かめる。
     *
     * <p>入力必須です。空のまま通すと 0 円の品が伝票に載り、
     * <b>お客さまは召し上がったのに請求されない</b>という形で店が損をします。
     * しかも金額が 0 なので、伝票を見ても気づきにくい。
     */
    private static int required(MenuItem item, Integer decidedPrice) {
        if (decidedPrice == null) {
            throw new OrderRejectedException(
                    "「%s」は時価の品です。今日の金額を入力してください".formatted(item.getName()));
        }
        if (decidedPrice <= 0) {
            throw new OrderRejectedException("金額は 1 円以上で入力してください");
        }
        if (decidedPrice > LIMIT) {
            throw new OrderRejectedException(
                    "金額が大きすぎます（1 品 %,d 円まで）。桁をご確認ください".formatted(LIMIT));
        }
        return decidedPrice;
    }

    /**
     * 時価でない品に金額が付いてきたら断る。
     *
     * <p><b>黙って捨てないのが要点です。</b>捨てると、店員は値引きしたつもりで
     * 送信でき、画面には「入れました」と出ます。食い違いに気づくのはお会計のときで、
     * そのときにはもうお客さまに別の金額を伝えたあとです。
     *
     * <p>値引きの手段としてここを開けないのは、単価が下がると
     * その卓の小計から他の品の代金が引かれる形になり、
     * 「どの品がいくらだったか」が伝票から読めなくなるためです（CLAUDE.md）。
     */
    private static int rejected(MenuItem item, Integer decidedPrice) {
        if (decidedPrice != null) {
            throw new OrderRejectedException(
                    "「%s」は %,d 円の品です。この画面から金額は変更できません"
                            .formatted(item.getName(), item.getPrice()));
        }
        return item.getPrice();
    }
}
