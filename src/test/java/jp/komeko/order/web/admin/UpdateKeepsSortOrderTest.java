package jp.komeko.order.web.admin;

import jp.komeko.order.domain.Category;
import jp.komeko.order.domain.DiningTable;
import jp.komeko.order.repository.CategoryRepository;
import jp.komeko.order.repository.DiningTableRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 編集画面の「更新」を押しても、並び順が動かないこと
 * （2026-09-19、店主の指示「卓は編集する画面で並び替えは出来ない仕様にして」）。
 *
 * <h2>これが今回いちばん危ない経路です</h2>
 *
 * <p>画面から {@code name="sortOrder"} を消すと、そのパラメータは送られてきません。
 * Spring は送られてこなかった項目の setter を呼ばないので、
 * Form のフィールドは<b>宣言時の初期値のまま</b>になります。
 * {@code TableForm.sortOrder} は {@code private Integer sortOrder = 0;} です。
 *
 * <pre>
 *   画面から欄を消す
 *     → sortOrder は送られてこない
 *     → setter が呼ばれない
 *     → getSortOrder() が 0 を返す（null ではない）
 *     → @NotNull も @Min(0) も 0 は通すので、検証エラーは出ない
 *     → setSortOrder(0) が走る
 *     → 一覧は並び順の昇順なので、その行が先頭へ飛ぶ
 * </pre>
 *
 * <p><b>例外もログも警告も出ません。</b>卓名を 1 文字直して「更新」を押しただけで
 * 並びが壊れ、次に一覧を見るまで誰も気づけません。
 * CLAUDE.md の「この種の破壊は例外を出さず、次に画面を見るまで気づけない」と同じ型です。
 *
 * <h2>直し方が 2 つに分かれた理由</h2>
 *
 * <pre>
 *   カテゴリ … AdminCategoryController の setSortOrder を消すだけ。
 *              @Transactional のダーティチェックなので、触らなければ何も起きない
 *   卓       … TableService.updateTable は必ず setSortOrder を呼ぶ。
 *              現在値を据え置く updateTableKeepingOrder を足した
 * </pre>
 *
 * <p>据え置きの読み取りは<b>必ず Service の中</b>で行います。コントローラで読んで渡すと
 * 読みと書きが別トランザクションになり、その隙間に {@code /place} が書いた
 * 新しい並びを古い値で踏み潰します。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("更新しても並び順は動かない")
class UpdateKeepsSortOrderTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private DiningTableRepository tableRepository;

    private Long categoryId;
    private Long tableId;

    @BeforeEach
    void setUp() {
        clear();
        // 先頭ではない並び順にしておく。0 で上書きされたら必ず値が変わる
        categoryId = categoryRepository.save(new Category("焼きもの", 20)).getId();
        categoryRepository.save(new Category("揚げもの", 30));

        tableId = tableRepository.save(new DiningTable("カウンター1", 2, 20)).getId();
        tableRepository.save(new DiningTable("テーブルA", 4, 30));
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private void clear() {
        tableRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    /**
     * ★ 卓：名前だけ直して更新しても、並び順は 20 のまま。
     *
     * <p>並び順を<b>送らずに</b>投げます。画面と同じ形です。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 卓：更新しても並び順は動かない")
    void updatingATableKeepsItsPlace() throws Exception {
        mockMvc.perform(post("/admin/tables/{id}", tableId).with(csrf())
                        .param("name", "カウンター壱")
                        .param("area", "カウンター")
                        .param("capacity", "2")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection());

        DiningTable after = tableRepository.findById(tableId).orElseThrow();
        assertThat(after.getName()).as("名前が更新されていない。試験の前提が崩れている")
                .isEqualTo("カウンター壱");
        assertThat(after.getSortOrder())
                .as("★ 並び順が動いた。画面に欄が無いぶん Form の初期値 0 が書かれている。"
                        + "更新するたびにその卓が一覧の先頭へ飛ぶ（エラーは出ない）")
                .isEqualTo(20);
    }

    /** ★ カテゴリ：名前だけ直して更新しても、並び順は 20 のまま。 */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ カテゴリ：更新しても並び順は動かない")
    void updatingACategoryKeepsItsPlace() throws Exception {
        mockMvc.perform(post("/admin/categories/{id}", categoryId).with(csrf())
                        .param("name", "焼きもの各種")
                        .param("visible", "true"))
                .andExpect(status().is3xxRedirection());

        Category after = categoryRepository.findById(categoryId).orElseThrow();
        assertThat(after.getName()).as("名前が更新されていない。試験の前提が崩れている")
                .isEqualTo("焼きもの各種");
        assertThat(after.getSortOrder())
                .as("★ 並び順が動いた。setSortOrder(form.getSortOrder()) が戻っている")
                .isEqualTo(20);
    }

    /**
     * ★ 並び順を送りつけても無視すること。
     *
     * <p>画面には欄がありませんが、URL を直接組み立てれば送れてしまいます。
     * 受け取って書いていると、「編集画面では並び替えできない」が
     * <b>見た目だけの決まり</b>になります。サーバ側で閉じておきます。
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("★ 並び順を送りつけても書き換わらない")
    void aHandCraftedSortOrderIsIgnored() throws Exception {
        mockMvc.perform(post("/admin/tables/{id}", tableId).with(csrf())
                        .param("name", "カウンター1")
                        .param("area", "カウンター")
                        .param("capacity", "2")
                        .param("active", "true")
                        .param("sortOrder", "1"))
                .andExpect(status().is3xxRedirection());
        assertThat(tableRepository.findById(tableId).orElseThrow().getSortOrder())
                .as("★ 送りつけた並び順が通った。画面から欄を消しただけで、"
                        + "サーバ側は受け取ったまま書いている")
                .isEqualTo(20);

        mockMvc.perform(post("/admin/categories/{id}", categoryId).with(csrf())
                        .param("name", "焼きもの")
                        .param("visible", "true")
                        .param("sortOrder", "1"))
                .andExpect(status().is3xxRedirection());
        assertThat(categoryRepository.findById(categoryId).orElseThrow().getSortOrder())
                .as("★ 送りつけた並び順が通った")
                .isEqualTo(20);
    }
}
