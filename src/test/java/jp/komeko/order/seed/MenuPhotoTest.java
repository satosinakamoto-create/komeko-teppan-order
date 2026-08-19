package jp.komeko.order.seed;

import jp.komeko.order.domain.MenuItem;
import jp.komeko.order.repository.MenuItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * メニュー写真が「登録されている」かつ「実在する」ことを固定するテスト。
 *
 * <p><b>なぜ 2 つ揃えて見るのか</b><br>
 * 商品に写真の<b>パスだけ</b>入っていて、ファイルが無い、という壊れ方があります。
 * このときアプリは何も言いません。例外も出ず、ログも出ず、
 * 画面には<b>壊れた画像アイコンが並ぶだけ</b>です。
 * 「登録されている」を見るテストだけでは、この状態を通してしまいます。
 *
 * <p>ファイル名を変えた・置き場所を移した、という変更で簡単に起きるので、
 * パスと実体の両方を突き合わせます。
 *
 * <p><b>なぜ静的ファイルなのか</b>（{@code /images/menu/} を見ている理由）<br>
 * 管理画面からのアップロード先（{@code data/uploads}）は、
 * 公開デモの環境では再起動のたびに消えます。
 * 最初から入れておく写真は jar に焼き込む必要がある、という設計判断です。
 * 置き場所が {@code /uploads/} に戻っていたら、それは設計が崩れた合図なので、
 * このテストが気づけるようにしています。
 */
/*
 * ★ seed-on-startup を有効にしている理由
 *
 *   test プロファイルは既定で false です。サンプルが入っていると
 *   「何件あるか」を数えるテストがサンプル次第で変わってしまうためで、
 *   その判断は正しい。
 *
 *   ただしこのテストが確かめたいのは、まさに「DataSeeder が写真を付けているか」です。
 *   投入を止めたままだと、商品が 1 件も無い DB を見て「写真ゼロ」と正しく報告し、
 *   そして何も守りません。通っていても無意味なテストになります。
 *   だからここだけ本物の投入を動かします。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.seed-on-startup=true")
@DisplayName("メニュー写真")
class MenuPhotoTest {

    @Autowired
    MenuItemRepository menuItemRepository;

    @Test
    @DisplayName("写真が付いている商品があり、そのファイルが実在する")
    void everyRegisteredPhotoExists() {
        List<MenuItem> withPhoto = menuItemRepository.findAll().stream()
                .filter(i -> i.getImagePath() != null)
                .toList();

        assertThat(withPhoto)
                .as("最初に目に入るカテゴリの主力に写真が無いと、"
                        + "UI/UX を見せる目的のデモとして成立しない")
                .isNotEmpty();

        for (MenuItem item : withPhoto) {
            String path = item.getImagePath();

            assertThat(path)
                    .as("%s の写真は静的ファイルに置くこと。"
                            + "アップロード先は公開デモの再起動で消える", item.getName())
                    .startsWith("/images/menu/");

            // "/images/menu/x.jpg" → クラスパス上の "static/images/menu/x.jpg"
            ClassPathResource file = new ClassPathResource("static" + path);
            assertThat(file.exists())
                    .as("%s の写真 %s が見つからない。"
                            + "パスだけ残ってファイルが無いと、画面には壊れた画像が並ぶ",
                            item.getName(), path)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("看板メニューには写真が付いている")
    void flagshipItemsHavePhotos() {
        // 名前を直接書いているのは、ここが「たまたま何品か付いている」ではなく
        // 「この商品には必ず付ける」という意思だから。
        // 商品名を変えるとこのテストが落ちるが、そのときは写真の割り当ても
        // 見直すべきなので、落ちてくれたほうがよい。
        List<String> flagship = List.of(
                "肉玉米粉そば",              // お好み焼き＝最初に開くカテゴリの先頭
                "たこ焼 8個",                // 看板メニュー
                "濃厚！国産豚ぺい焼",        // 鉄板おつまみのおすすめ
                "米粉麺焼きそば（ソース）");  // 鉄板麺のおすすめ

        for (String name : flagship) {
            MenuItem item = menuItemRepository.findAll().stream()
                    .filter(i -> name.equals(i.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("商品が見つからない: " + name));

            assertThat(item.getImagePath())
                    .as("%s は写真を出すと決めた商品", name)
                    .isNotNull();
        }
    }
}
