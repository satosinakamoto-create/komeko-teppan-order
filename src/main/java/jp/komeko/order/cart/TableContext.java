package jp.komeko.order.cart;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;

/**
 * 「いまブラウザを操作している人が、どの卓にいるか」を覚えておく入れ物。
 *
 * <p>お客さんが卓の QR（{@code /t/{トークン}}）を読むと、ここに卓の情報が入ります。
 * 以降は普通のメニュー URL（{@code /}、{@code /items/12}）を回っても
 * 「3番テーブルの人だ」と分かるので、注文を正しい伝票に足せます。
 *
 * <p>{@link Cart} と分けているのは、
 * <b>カートを空にしても卓の紐づけは消えてほしくない</b>からです。
 * 注文を確定するとカートは空になりますが、お客さんはまだ席にいます。
 *
 * <p>{@code @SessionScope} なので、ブラウザ（セッション）ごとに 1 つ作られます。
 * 同じ卓に座っていても、スマホが違えば別のインスタンスになります。
 * それでも同じ卓の伝票に注文が集まるのは、卓 ID をキーにしているからです。
 */
@Component
@SessionScope
public class TableContext implements Serializable {

    private Long tableId;
    private String tableName;
    private String accessToken;

    /** 卓に入店した（QR を読んだ）。 */
    public void bind(Long tableId, String tableName, String accessToken) {
        this.tableId = tableId;
        this.tableName = tableName;
        this.accessToken = accessToken;
    }

    /** 卓の紐づけを外す（会計後や、別の卓の QR を読んだとき）。 */
    public void clear() {
        this.tableId = null;
        this.tableName = null;
        this.accessToken = null;
    }

    /** 卓が紐づいているか。false ならメニューを見せる前に QR を読んでもらう。 */
    public boolean isBound() {
        return tableId != null;
    }

    public Long getTableId() {
        return tableId;
    }

    public String getTableName() {
        return tableName;
    }

    public String getAccessToken() {
        return accessToken;
    }
}
