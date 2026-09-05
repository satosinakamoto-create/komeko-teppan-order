package jp.komeko.order.web.admin.form;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * オプションの追加料金の入力チェック。
 *
 * <p><b>ここで守っているもの＝単価がマイナスにならないこと</b>
 *
 * <p>追加料金はもともと −10,000 円まで許していました。
 * 「ソース抜きで −50 円」のような値引きを表現するためです。
 * ところが {@code OrderLine#recalculate} は
 * {@code basePrice + オプション代} をそのまま単価にしていて、下限で止めていません。
 * 商品が ¥600〜¥1,680 のこの店で −1,000 円のオプションを付けると、
 * <b>単価が負になり、その卓の小計から他の品の代金が差し引かれます</b>。
 * 「+1000」と入れるつもりで「-1000」と打つだけで起こせます。
 *
 * <p>商品の価格側は {@code MenuItemForm} が {@code @Min(0)} なので、
 * <b>ここを 0 以上に閉じれば、単価は構造的に負になりません</b>。
 * 注文時に単価を検査する「最後の網」を足す必要がないのは、そのためです。
 * だからこの 1 本が、負の単価を防ぐ唯一の歯止めになっています。
 *
 * <p>Spring を起動しない素の JUnit で書いています
 * （入力チェックの確認に DB もコンテキストも要らないため）。
 */
@DisplayName("オプションの追加料金")
class OptionChoiceFormValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /** 追加料金だけを差し替えた、他は正しいフォームを作る。 */
    private OptionChoiceForm formWithPrice(int extraPrice) {
        OptionChoiceForm form = new OptionChoiceForm();
        form.setName("チーズ追加");
        form.setExtraPrice(extraPrice);
        form.setSortOrder(0);
        return form;
    }

    private Set<ConstraintViolation<OptionChoiceForm>> validate(OptionChoiceForm form) {
        return validator.validate(form);
    }

    @Test
    @DisplayName("★ マイナスの追加料金は受け付けない（単価が負になるため）")
    void rejectsNegativeExtraPrice() {
        assertThat(validate(formWithPrice(-1000)))
                .as("−1,000 円は弾く")
                .isNotEmpty();

        // 下限ぎりぎりも弾く。−1 円でも積み重なれば単価は負になる
        assertThat(validate(formWithPrice(-1)))
                .as("−1 円も弾く")
                .isNotEmpty();
    }

    @Test
    @DisplayName("0 円と、ふつうの追加料金は通る")
    void acceptsZeroAndNormalExtraPrice() {
        assertThat(validate(formWithPrice(0)))
                .as("「大盛り +0 円」のような選択肢は作れる")
                .isEmpty();
        assertThat(validate(formWithPrice(150)))
                .as("チーズ追加 +150 円")
                .isEmpty();
    }

    @Test
    @DisplayName("上限を超える追加料金は受け付けない")
    void rejectsTooLargeExtraPrice() {
        // 桁を打ち間違えたときの歯止め。こちらは以前からある
        assertThat(validate(formWithPrice(100_001))).isNotEmpty();
    }
}
