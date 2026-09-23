package jp.komeko.order.inventory.web.form;

import jakarta.validation.Valid;

import java.util.ArrayList;
import java.util.List;

/**
 * レシピの取り込み確認画面ぜんぶ（設計 ト09b 806:8314）。
 *
 * <p><b>この画面は「AI が読む → 人が照合して直す → まとめて保存」です。</b>
 * レシート確認画面（{@code purchase-form.html} の confirm）と同じ形で、
 * <b>1 つの {@code <form>} がカードも材料行もフッターも全部包みます</b>。
 * 行の追加・削除・その場で食材作成は、どれもこのフォーム全部を POST して
 * 描き直すだけなので、<b>入力済みの他の行は一切消えません</b>。
 *
 * <p><b>JavaScript を使いません。</b>この画面は「記録の責任は人が持つ」場所で、
 * 画面の状態とサーバの form が必ず一致していてほしいためです
 * （レシート確認画面から受け継いだ判断）。
 *
 * <p><b>AI 読取が無くても成立します。</b>空の状態で開いて手で入力しても
 * まったく同じように動きます。AI は後からこの入れ物に値を詰めるだけです。
 * {@code ANTHROPIC_API_KEY} が無い環境でも取り込み画面は使えます。
 */
public class RecipeImportForm {

    /**
     * 読み取り元の画像（{@code /uploads/xxx.jpg}）。手入力なら null。
     *
     * <p>hidden で持ち回ります。<b>セッションに置きません。</b>
     * 確認画面は行を足すたびに何度も描き直されるので、
     * フォームに載せておくほうが失われる余地がありません（レシートと同じ）。
     */
    private String imagePath;

    /** 読み取り元のファイル名。画面の「読み取り元：…」に出すだけ。 */
    private String sourceName;

    /** AI の生の応答。あとで読み違いを追えるように丸ごと残す。手入力なら null。 */
    private String readingJson;

    @Valid
    private List<RecipeImportItemForm> items = new ArrayList<>();

    /** 手入力で開くときの初期状態。商品カード 1 枚＋空の材料行。 */
    public static RecipeImportForm manual() {
        RecipeImportForm form = new RecipeImportForm();
        form.items.add(RecipeImportItemForm.manual());
        return form;
    }

    /** 中身のあるカードだけ（空のカードは保存で無視する）。 */
    public List<RecipeImportItemForm> filledItems() {
        List<RecipeImportItemForm> result = new ArrayList<>();
        for (RecipeImportItemForm item : items) {
            if (!item.isBlank()) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 登録できるカードの数。見出しとボタンの「◯ 品をまとめて登録」に使う。
     *
     * <p>照合できていないカードは数えません。<b>押す前に何品入るかが分かる</b>
     * ようにするためで、押してから「1 品しか入りませんでした」と言われるより親切です。
     */
    public int readyCount() {
        int count = 0;
        for (RecipeImportItemForm item : filledItems()) {
            if (item.isReady()) {
                count++;
            }
        }
        return count;
    }

    /** 読み取った材料の総数。見出しの補足「◯ 品・◯ 材料を読み取りました」に使う。 */
    public int lineCount() {
        int count = 0;
        for (RecipeImportItemForm item : filledItems()) {
            count += item.filledLines().size();
        }
        return count;
    }

    /** 人が直さないと登録されないものが残っているか。フッターの注意書きの出し分け。 */
    public boolean hasUnmatched() {
        for (RecipeImportItemForm item : filledItems()) {
            if (item.isUnmatched()) {
                return true;
            }
            for (RecipeImportLineForm line : item.filledLines()) {
                if (line.isUnmatched()) {
                    return true;
                }
            }
        }
        return false;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getReadingJson() {
        return readingJson;
    }

    public void setReadingJson(String readingJson) {
        this.readingJson = readingJson;
    }

    public List<RecipeImportItemForm> getItems() {
        return items;
    }

    public void setItems(List<RecipeImportItemForm> items) {
        this.items = items;
    }
}
