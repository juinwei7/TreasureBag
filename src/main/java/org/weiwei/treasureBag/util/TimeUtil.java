package org.weiwei.treasureBag.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.Date;
import java.util.List;

public class TimeUtil {

    public static final String DAY = "DAY";
    public static final String WEEK = "WEEK";
    public static final String MONTH = "MONTH";

    /**
     * 預設時區：台北時間
     */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Taipei");
    /**
     * ISO 週字段設定：週一為一週的第一天，且每週至少一天
     */
    private static final WeekFields ISO_WEEK = WeekFields.of(DayOfWeek.MONDAY, 1);

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    private static final List<String> PATTERNS = List.of("yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy/MM/dd");

    // 時間格式化字串
    public static String M = Message.getMsg(Message.FORMAT__TIME_M);
    public static String H = Message.getMsg(Message.FORMAT__TIME_H);
    public static String D = Message.getMsg(Message.FORMAT__TIME_D);
    public static String S = Message.getMsg(Message.FORMAT__TIME_S);

    // 獲取冷卻時間的字串表示
    public static String getCooldownString(long totalSeconds) {
        if (totalSeconds <= -1L) return "§c" + Message.getMsg(Message.ADMIN_MESSAGE__EDIT_DISABLED);
        long days = totalSeconds / (24 * 3600);
        long hours = (totalSeconds % (24 * 3600)) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder("§a");
        if (days > 0) sb.append(days).append(D).append(" ");
        if (hours > 0) sb.append(hours).append(H).append(" ");
        if (minutes > 0) sb.append(minutes).append(M).append(" ");
        if (seconds > 0 || sb.length() == 2) sb.append(seconds).append(S).append(" ");

        return sb.toString().trim();
    }

    /**
     * 將指定日期加上天數（若傳入 null 則回傳 null）
     *
     * @param date 原始日期
     * @param days 要加的天數
     * @return 新的 Date
     */
    public static Date addDays(Date date, int days) {
        if (date == null) return null;
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime ldt = LocalDateTime.ofInstant(date.toInstant(), zone).plusDays(days);
        return Date.from(ldt.atZone(zone).toInstant());
    }

    /**
     * 將 Date 轉為可顯示的字串，若為 null 則回傳 "-"
     *
     * @param date 要格式化的日期
     * @return 格式化後的字串或 "-"（無日期時）
     */
    public static CharSequence formatDate(Date date) {
        if (date == null) return "-";
        return DATE_FMT.format(date);
    }

    /**
     * 將 Date 轉為可顯示的字串，可指定格式；若 date 為 null 回傳 "-"
     *
     * @param date   要格式化的日期
     * @param format 自訂格式字串，若為 null/空則使用預設格式
     * @return 格式化後的字串或 "-"（無日期時）
     */
    public static CharSequence formatDate(Date date, String format) {
        if (date == null) {
            return "-";
        }
        String pattern = (format == null || format.isEmpty()) ? DATE_FMT.toPattern() : format;
        SimpleDateFormat sdf = new SimpleDateFormat(pattern);
        return sdf.format(date);
    }

    /**
     * 將字串依序用多種格式嘗試解析為 Date，若皆無法解析則回傳 null
     *
     * @param dateStr 要解析的字串
     * @return 解析後的 Date，或 null
     */
    public static Date parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        for (String pattern : PATTERNS) {
            try {
                return new SimpleDateFormat(pattern).parse(dateStr);
            } catch (ParseException ignored) {
            }
        }
        // 全部格式都解析失敗
        return null;
    }

    /**
     * 當前時間加上指定秒數
     *
     * @param seconds 要加的秒數
     */
    public static Date addSeconds(int seconds) {
        if (seconds <= 0) return new Date();
        long currentTimeMillis = System.currentTimeMillis();
        return new Date(currentTimeMillis + seconds * 1000L);
    }

    /**
     * 判斷每日冷卻是否已過期（跨天即可再次領取）
     *
     * @param lastReceive 上次領取時間，若為 null 表示從未領取
     * @return 若未在同一天領取則返回 true
     */
    public static boolean isDailyCooldownExpired(Date lastReceive) {
        if (lastReceive == null) {
            return true;
        }
        LocalDate lastDate = Instant.ofEpochMilli(lastReceive.getTime())
                .atZone(DEFAULT_ZONE)
                .toLocalDate();
        LocalDate today = LocalDate.now(DEFAULT_ZONE);
        // 非同一天才算過期
        return !lastDate.isEqual(today);
    }

    /**
     * 判斷每週冷卻是否已過期（跨週即可再次領取）
     *
     * @param lastReceive 上次領取時間，若為 null 表示從未領取
     * @return 若進入新一週則返回 true
     */
    public static boolean isWeeklyCooldownExpired(Date lastReceive) {
        if (lastReceive == null) {
            return true;
        }
        LocalDate lastDate = Instant.ofEpochMilli(lastReceive.getTime())
                .atZone(DEFAULT_ZONE)
                .toLocalDate();
        LocalDate today = LocalDate.now(DEFAULT_ZONE);

        int lastWeek = lastDate.get(ISO_WEEK.weekOfWeekBasedYear());
        int lastYear = lastDate.get(ISO_WEEK.weekBasedYear());
        int currentWeek = today.get(ISO_WEEK.weekOfWeekBasedYear());
        int currentYear = today.get(ISO_WEEK.weekBasedYear());
        // 當前週或週年份大於上次，表示已跨週
        return currentYear > lastYear || currentWeek > lastWeek;
    }

    /**
     * 判斷每月冷卻是否已過期（跨月即可再次領取）
     *
     * @param lastReceive 上次領取時間，若為 null 表示從未領取
     * @return 若進入新一月則返回 true
     */
    public static boolean isMonthlyCooldownExpired(Date lastReceive) {
        if (lastReceive == null) {
            return true;
        }
        LocalDate lastDate = Instant.ofEpochMilli(lastReceive.getTime())
                .atZone(DEFAULT_ZONE)
                .toLocalDate();
        LocalDate today = LocalDate.now(DEFAULT_ZONE);
        // 年或月不同即跨月
        return lastDate.getYear() != today.getYear() || lastDate.getMonthValue() != today.getMonthValue();
    }

    /**
     * 計算下一次可領取的日期（每日情況：隔天；每週情況：下一個週一；每月情況：下月一日）
     *
     * @param lastReceive 上次領取時間，若為 null 表示今天即可
     * @param unit        單位："DAY"、"WEEK" 或 "MONTH"
     * @return 下一次可領取的 LocalDate
     * @throws IllegalArgumentException 若單位不在支援範圍
     */
    public static LocalDate nextAvailableDate(Date lastReceive, String unit) {
        LocalDate reference = (lastReceive == null)
                ? LocalDate.now(DEFAULT_ZONE)
                : Instant.ofEpochMilli(lastReceive.getTime()).atZone(DEFAULT_ZONE).toLocalDate();
        switch (unit) {
            case DAY:
                return reference.plusDays(1);
            case WEEK:
                return reference.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            case MONTH:
                return reference.withDayOfMonth(1).plusMonths(1);
            default:
                throw new IllegalArgumentException("不支援的單位: " + unit);
        }
    }

    /**
     * 計算下次領取的剩餘秒數
     *
     * @param lastReceive 上次領取時間，若為 null 表示今天即可
     * @param unit        單位："DAY"、"WEEK" 或 "MONTH"
     * @return 剩餘秒數
     * @throws IllegalArgumentException 若單位不在支援範圍
     */
    public static long getRemainingSeconds(Date lastReceive, String unit) {
        LocalDate nextDate = nextAvailableDate(lastReceive, unit);
        LocalDateTime nextDateTime = nextDate.atStartOfDay(DEFAULT_ZONE).toLocalDateTime();
        LocalDateTime now = LocalDateTime.now(DEFAULT_ZONE);
        return Duration.between(now, nextDateTime).getSeconds();
    }
}
