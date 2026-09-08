package com.desmond.gptwake;

import static org.junit.Assert.*;
import org.junit.Test;

public class JapaneseTextTest {
    @Test
    public void spellingWidthAndCombiningMarksHaveTheSameReading() {
        assertEquals(JapaneseText.reading("おはようございます"),
                JapaneseText.reading("ｵﾊﾖｳｺﾞｻﾞｲﾏｽ"));
        assertEquals(JapaneseText.reading("がっこう"), JapaneseText.reading("か\u3099っこう"));
        assertEquals("とうきょう", JapaneseText.reading("東京"));
        assertEquals(JapaneseText.reading("東京"), JapaneseText.reading("トーキョー"));
    }

    @Test
    public void acronymAndKanaCanMatchSpacedModelOutput() {
        String phrase = "ねえジーピーティー";
        assertTrue(JapaneseText.matches("ね え Ｇ Ｐ Ｔ。", phrase, JapaneseText.reading(phrase)));
        assertEquals(JapaneseText.reading("チャットジーピーティー"), JapaneseText.reading("ChatGPT"));
    }

    @Test
    public void onlyACompletePhraseMatches() {
        String phrase = "おはようございます";
        String reading = JapaneseText.reading(phrase);
        assertTrue(JapaneseText.matches(" お は よ う ご ざ い ま す。", phrase, reading));
        assertFalse(JapaneseText.matches("明日はおはようございますと言って", phrase, reading));
        assertFalse(JapaneseText.matches("おはようございます。こんにちは。", phrase, reading));
        assertFalse(JapaneseText.matches("おはよう", phrase, reading));
        assertFalse(JapaneseText.matches("", phrase, reading));
        assertFalse(JapaneseText.matches(null, phrase, reading));
    }

    @Test
    public void voicingSmallKanaAndGeminationRemainDistinct() {
        assertNotEquals(JapaneseText.reading("がっこう"), JapaneseText.reading("がこう"));
        assertNotEquals(JapaneseText.reading("びょういん"), JapaneseText.reading("びよういん"));
        assertNotEquals(JapaneseText.reading("かく"), JapaneseText.reading("がく"));
    }

    @Test
    public void alternateKanjiReadingCanBeSpecified() {
        assertTrue(JapaneseText.matches("明日", "明日", "あす"));
        assertTrue(JapaneseText.matches("あす", "明日", "あす"));
        assertFalse(JapaneseText.matches("あした", "明日", "あす"));
    }
}
