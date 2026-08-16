package jp.komeko.order.service.dto;

import java.time.LocalDate;

/**
 * 期間集計の 1 日分。
 *
 * @param date   営業日
 * @param orders 注文件数
 * @param amount 売上金額（税込・円）
 */
public record DaySummary(LocalDate date, long orders, long amount) {
}
