package jp.komeko.order.inventory.web;

import jp.komeko.order.inventory.config.InventoryProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 「仕入れ・在庫モジュールが有効か」を、すべての画面に渡す。
 *
 * <p>サイドバーのリンクを出すかどうかの判断に使います。
 * モジュールが無効なとき、リンクだけ残っていると押した人が 404 に落ちます。
 *
 * <p><b>このクラス自体は常に存在します。</b>
 * {@code @ConditionalOnProperty} を付けてしまうと、無効時に
 * {@code ${inventoryEnabled}} が未定義になり、テンプレート側で判定できません。
 * 「機能は無いが、無いことは分かる」状態にしておくのが安全です。
 *
 * <p>既存の {@code GlobalModelAttributes} に追記すれば済む話ですが、
 * それは既存ファイルの変更になります。自分の側に閉じておきます。
 */
@ControllerAdvice
public class InventoryModelAttributes {

    private final InventoryProperties properties;

    public InventoryModelAttributes(InventoryProperties properties) {
        this.properties = properties;
    }

    @ModelAttribute("inventoryEnabled")
    public boolean inventoryEnabled() {
        return properties.enabled();
    }
}
