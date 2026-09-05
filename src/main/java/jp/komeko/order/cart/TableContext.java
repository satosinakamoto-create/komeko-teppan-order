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

    /*
     * ★ 明示すること。書かないと、項目を 1 つ足しただけで
     *   お客さま全員の卓の紐づけが切れます（2026-09-06 に実際に起こしました）。
     *
     *   Tomcat は再起動をまたいでセッションをファイルに保存し、起動時に読み戻します。
     *   serialVersionUID を書いていないとコンパイラが中身から自動生成するので、
     *   フィールドを 1 つ足すだけで値が変わり、読み戻しに失敗します。
     *
     *     InvalidClassException: local class incompatible:
     *       stream classdesc serialVersionUID = 7968869593580997768,
     *       local  class      serialVersionUID = -5112690952452925035
     *
     *   こうなると保存されていたセッションが<b>まるごと捨てられ</b>、
     *   お客さまの画面は「お席の QR をお読みください」に戻ります。
     *   店の営業中にアプリを入れ替えたら、その瞬間に全卓が飛ぶということです。
     *
     *   固定しておけば、あとから足した項目は既定値（null / 0）で読み戻せます。
     *   逆に「型を変える」「意味を変える」ときは、古い値が入ってくることを
     *   前提に書くか、番号を上げて切り捨てるかを意識して決めてください。
     */
    private static final long serialVersionUID = 1L;

    private Long tableId;
    private String tableName;
    private String accessToken;

    /**
     * このブラウザがついていた伝票の id。
     *
     * <p><b>会計が済んだあとに、その伝票の中身を見せるために持ちます。</b>
     * 締めると「その卓のいま開いている伝票」は無くなるので、
     * 卓の id からは自分がいくら払ったのかを辿れなくなります。
     *
     * <p><b>卓から引き直してはいけません。</b>
     * 「その卓の最後に締まった伝票」を出すと、同じ席に次の組が入って
     * 会計まで済ませたとき、前の組のスマホに<b>次の組の伝票</b>が出ます。
     * 見ているのは金額と、何を頼んだかです。他人には見せられません。
     *
     * <p>ここに覚えた id は、このブラウザのセッションの中だけにあります。
     */
    private Long sessionId;

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
        this.sessionId = null;
    }

    /**
     * いまついている伝票を覚える。
     *
     * <p>入店したときと、伝票を開いたときに呼びます。
     * 会計後にこの id で読み直して「お会計の内容」を出します。
     */
    public void rememberSession(Long sessionId) {
        this.sessionId = sessionId;
    }

    /** 覚えている伝票の id。一度も伝票につかないまま来た人は null。 */
    public Long getSessionId() {
        return sessionId;
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
