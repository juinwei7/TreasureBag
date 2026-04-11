package org.weiwei.treasureBag.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ############################################
 * #                                          #
 * #             隨機類別 Random               #
 * #                                          #
 * ############################################
 */


public class RandomUtil {

    private static final java.util.Random random = new java.util.Random();
    private static final char[] LOWERCASE = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final char[] DIGITS = "0123456789".toCharArray();

    public static int getRandom(int min, int max) {
        return (int) (Math.random() * (max - min + 1) + min);
    }

    /**
     * 根據傳入的機率 (1-100) 返回布林值
     *
     * @param probability 機率 (1-100)
     * @return true 代表命中機率, false 代表未命中
     */
    public static boolean isHit(double probability) {
        if (probability < 1) return false; // 低於 1% 則不可能命中
        if (probability > 100) return true; // 超過 100% 則一定命中
        return random.nextInt(100) < probability;
    }

    /**
     * 給予隨機浮點數
     * @param min 最小值
     * @param max 最大值
     * @return 隨機浮點數
     */
    public static double getRandomDouble(double min, double max, int scale) {
        if (min >= max) return 0;
        double raw = min + (max - min) * random.nextDouble();
        double factor = Math.pow(10, scale);
        return Math.round(raw * factor) / factor;
    }

    /**
     * 獲取隨機字串
     *
     * @param length           字串長度
     * @param includeLowercase 是否包含小寫字母
     * @param includeUppercase 是否包含大寫字母
     */
    public static String getRandomString(int length, boolean includeLowercase, boolean includeUppercase, boolean includeDigits) {
        List<Character> pool = new ArrayList<>();
        if (includeLowercase) for (char c : LOWERCASE) pool.add(c);
        if (includeUppercase) for (char c : UPPERCASE) pool.add(c);
        if (includeDigits) for (char c : DIGITS) pool.add(c);

        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            char c = pool.get(random.nextInt(pool.size()));
            sb.append(c);
        }

        return sb.toString();
    }

    public static String getRandomString(int length) {
        return getRandomString(length, true, true, true);
    }

}
