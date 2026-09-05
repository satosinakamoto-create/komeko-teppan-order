package jp.komeko.order.inventory.repository;

import jp.komeko.order.domain.SessionStatus;
import jp.komeko.order.domain.TableSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

/**
 * 「その期間、いくら売れたか」を既存の会計データから読むだけの入口。
 *
 * <p><b>読むだけです。書きません。</b>既存のリポジトリに問い合わせを足せば
 * 済む話ですが、それは既存ファイルの変更になります。在庫モジュールの都合を
 * 既存側に持ち込まないため、こちら側に別の口を用意しています。
 *
 * <p><b>数えるのは「閉じた伝票」です。注文ではありません（2026-08-31 変更）。</b><br>
 * はじめは受渡済みの注文（{@code Order}）を合計していましたが、それだと
 * テーブルチャージ（1 人 450 円）と深夜料金が入らず、
 * <b>売上画面と違う「売上」で原価率を割っていました</b>。
 * 帳簿上の売上とは会計で確定した金額のことで、それは伝票（{@code TableSession}）が
 * 持っています。原価率は税理士に見せる数字なので、売上画面と一致しない分母は使えません。
 *
 * <p>この置き場所には、将来ここに乗るものがすべて自動で入る、という利点もあります。
 * 食べ放題のプラン料金も、クーポンの値引きも、伝票レベルの加減算として
 * {@code TableSession.recalculate} に足す設計なので（設計文書 10 章）、
 * そのとき<b>この問い合わせは 1 文字も変えずに正しいまま</b>です。
 */
public interface SalesLookupRepository extends JpaRepository<TableSession, Long> {

    /**
     * 期間の売上（税込）と、そこに含まれる消費税額の合計。
     *
     * <p>営業日（{@code businessDate}）で区切ります。深夜 1 時の会計が
     * 翌日の売上に化けないよう、暦の日付ではなく営業日を使うのが既存の流儀です。
     *
     * @param status 通常は {@link SessionStatus#CLOSED}（会計済みだけを数える。
     *               開いている伝票はまだ金額が動くので売上ではない）
     */
    @Query("""
            select coalesce(sum(s.totalAmount), 0) as grossAmount,
                   coalesce(sum(s.taxAmount), 0)   as taxAmount
            from TableSession s
            where s.businessDate between :from and :to and s.status = :status
            """)
    SalesTotal sumSales(@Param("from") LocalDate from,
                        @Param("to") LocalDate to,
                        @Param("status") SessionStatus status);

    /** 期間の売上合計。{@link #sumSales} の戻り値。 */
    interface SalesTotal {
        Long getGrossAmount();

        Long getTaxAmount();
    }
}
