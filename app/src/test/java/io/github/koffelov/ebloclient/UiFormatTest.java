package io.github.koffelov.ebloclient;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Counters, durations and sizes formatted like the site shows them. */
public class UiFormatTest {
    @Test
    public void counts() {
        assertEquals("999", Ui.count(999));
        assertEquals("1k", Ui.count(1000));
        assertEquals("1,2k", Ui.count(1234));
        assertEquals("2,5M", Ui.count(2_500_000));
    }

    @Test
    public void durations() {
        assertEquals("", Ui.duration(0));
        assertEquals("1:05", Ui.duration(65));
        assertEquals("28:56", Ui.duration(1736));
        assertEquals("1:01:01", Ui.duration(3661));
    }

    @Test
    public void sizes() {
        assertEquals("512 Б", Ui.size(512));
        assertEquals("2 КБ", Ui.size(2048));
        assertEquals("5,5 МБ", Ui.size(5_767_168));
        assertEquals("200 МБ", Ui.size(209_715_200));
    }
}
