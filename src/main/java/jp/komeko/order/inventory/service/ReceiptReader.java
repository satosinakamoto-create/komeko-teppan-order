package jp.komeko.order.inventory.service;

/**
 * レシート画像を読んで、品目と金額を取り出す係。
 *
 * <p><b>インタフェースにしてある理由</b><br>
 * 読み取りの中身（どの AI を、どう呼ぶか）は将来まちがいなく変わります。
 * 呼び出し側（画面）はここだけを知っていればよく、
 * 実装を差し替えても画面のコードは 1 行も変わりません。
 * テストでも、本物の API を呼ばずに決まった結果を返す実装を差し込めます。
 *
 * <p><b>読めなくても止めない</b><br>
 * この機能は「あると速い」ものであって、「ないと記録できない」ものではありません。
 * API キーが無いとき、通信に失敗したとき、レシートがぐちゃぐちゃで読めないとき、
 * いずれも例外で止めず {@link ReceiptReading#empty()} を返します。
 * 人は手入力に切り替えて先へ進めます。
 */
public interface ReceiptReader {

    /** 読取機能が使える状態か（API キーが設定されているか）。 */
    boolean isAvailable();

    /**
     * レシート画像を読む。
     *
     * @param imageBytes  画像のバイト列
     * @param contentType {@code image/jpeg} など
     * @return 読み取り結果。失敗しても例外は投げず、空の結果を返す
     */
    ReceiptReading read(byte[] imageBytes, String contentType);
}
