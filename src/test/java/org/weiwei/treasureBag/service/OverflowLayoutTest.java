package org.weiwei.treasureBag.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超出格取出介面的分頁與「身上放不放得下」
 */
class OverflowLayoutTest {

    @Test
    void 頁數至少一頁_每頁四十五格() {
        assertEquals(1, OverflowLayout.pageCount(0));
        assertEquals(1, OverflowLayout.pageCount(1));
        assertEquals(1, OverflowLayout.pageCount(45));
        assertEquals(2, OverflowLayout.pageCount(46));
        // 225 格縮成 45 格：最多 180 格超出，剛好 4 頁
        assertEquals(4, OverflowLayout.pageCount(180));
        assertEquals(5, OverflowLayout.pageCount(181));
    }

    @Test
    void 頁碼夾在範圍內() {
        assertEquals(0, OverflowLayout.clampPage(-1, 100));
        assertEquals(2, OverflowLayout.clampPage(2, 100));
        assertEquals(2, OverflowLayout.clampPage(5, 100));
        // 最後一頁唯一的一件被拿走：退回前一頁
        assertEquals(0, OverflowLayout.clampPage(1, 45));
        // 全部拿完：停在第一頁
        assertEquals(0, OverflowLayout.clampPage(3, 0));
    }

    @Test
    void 格子對到清單第幾件() {
        assertEquals(0, OverflowLayout.indexAt(0, 0, 100));
        assertEquals(44, OverflowLayout.indexAt(0, 44, 100));
        assertEquals(45, OverflowLayout.indexAt(1, 0, 100));
        assertEquals(99, OverflowLayout.indexAt(2, 9, 100));
        // 最後一頁後面的空格
        assertEquals(-1, OverflowLayout.indexAt(2, 10, 100));
        // 控制列、畫面外、負頁碼
        assertEquals(-1, OverflowLayout.indexAt(0, 45, 100));
        assertEquals(-1, OverflowLayout.indexAt(0, -999, 100));
        assertEquals(-1, OverflowLayout.indexAt(-1, 0, 100));
        // 沒東西
        assertEquals(-1, OverflowLayout.indexAt(0, 0, 0));
    }

    @Test
    void 有空格就放得下() {
        assertTrue(OverflowLayout.fits(64, 64, 0, 1));
        assertTrue(OverflowLayout.fits(1, 1, 0, 1));
        assertFalse(OverflowLayout.fits(1, 1, 0, 0));
    }

    @Test
    void 同種的堆疊得下就不用空格() {
        assertTrue(OverflowLayout.fits(10, 64, 10, 0));
        assertTrue(OverflowLayout.fits(10, 64, 30, 0));
        assertFalse(OverflowLayout.fits(11, 64, 10, 0));
        // 疊一部分、剩下的佔一個空格
        assertTrue(OverflowLayout.fits(64, 64, 10, 1));
    }

    @Test
    void 超過一組上限的要拆成好幾格() {
        // 背包裡存著 100 個一組上限 64 的物品：要兩個空格
        assertFalse(OverflowLayout.fits(100, 64, 0, 1));
        assertTrue(OverflowLayout.fits(100, 64, 0, 2));
        // 先疊掉 36 個，剩 64 個一格剛好
        assertTrue(OverflowLayout.fits(100, 64, 36, 1));
        // 不能疊的物品堆了 3 個：要 3 格
        assertFalse(OverflowLayout.fits(3, 1, 0, 2));
        assertTrue(OverflowLayout.fits(3, 1, 0, 3));
    }

    @Test
    void 異常數值不會算成放得下() {
        // 上限讀成 0 時當成一格一件，不會除以零
        assertTrue(OverflowLayout.fits(2, 0, 0, 2));
        assertFalse(OverflowLayout.fits(2, 0, 0, 1));
        // 負的疊放空間當成 0
        assertFalse(OverflowLayout.fits(1, 64, -5, 0));
    }
}
