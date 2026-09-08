package com.desmond.gptwake;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WakeWordTokenizerTest {
    private final WakeWordTokenizer tokenizer = new WakeWordTokenizer();

    @Before
    public void loadDictionary() throws Exception {
        tokenizer.load(RuntimeEnvironment.getApplication().getAssets());
    }

    @Test
    public void defaultPhraseMatchesBundledTokens() {
        assertEquals(WakeWordStore.DEFAULT_LINE,
                tokenizer.convert(WakeWordStore.DEFAULT_PHRASE).keywordLine);
    }

    @Test
    public void adjacentEnglishAndChineseAreBothConverted() {
        var result = tokenizer.convert("open芝麻");
        assertTrue(result.ok);
        assertEquals("OW1 P AH0 N zh ī m á", result.tokens);
    }

    @Test
    public void shortPhraseWarningCountsPronouncedSyllables() {
        var result = tokenizer.convert("strengths");
        assertTrue(result.ok);
        assertEquals(WakeWordTokenizer.Err.TOO_SHORT, result.err);
        assertEquals(1, result.errCount);
    }

    @Test
    public void keywordControlCharactersAreRejected() {
        assertFalse(tokenizer.convert("open #0.0 @anything").ok);
        assertFalse(tokenizer.convert("open/sesame").ok);
    }

    @Test
    public void japaneseUsesReadingsInsteadOfChineseModelTokens() {
        var result = tokenizer.convert("東京", WakeLanguage.JAPANESE, "");
        assertTrue(result.ok);
        assertEquals("とうきょう", result.readable);
        assertEquals("", result.tokens);
        assertEquals("", result.keywordLine);
        assertNotEquals(result.readable, tokenizer.convert("東京").readable);
    }

    @Test
    public void kanaInChineseModeExplainsWhichLanguageToSelect() {
        assertEquals(WakeWordTokenizer.Err.JAPANESE_LANGUAGE_REQUIRED,
                tokenizer.convert("こんにちは").err);
    }

    @Test
    public void japaneseNormalizesWidthAndAcceptsExplicitNameReadings() {
        assertEquals(tokenizer.convert("おはようございます", WakeLanguage.JAPANESE, "").readable,
                tokenizer.convert("ｵﾊﾖｳｺﾞｻﾞｲﾏｽ", WakeLanguage.JAPANESE, "").readable);
        var name = tokenizer.convert("𠮷野", WakeLanguage.JAPANESE, "ヨシノ");
        assertTrue(name.ok);
        assertEquals("よしの", name.readable);
    }

    @Test
    public void japaneseRejectsControlCharactersAndInvalidReadings() {
        for (String phrase : new String[]{"ねえ #0 @anything", "こんにちは/東京", "こんにちは😀",
                "ー", "あ".repeat(41)}) {
            assertFalse(phrase, tokenizer.convert(phrase, WakeLanguage.JAPANESE, "").ok);
        }
        assertFalse(tokenizer.convert("東京", WakeLanguage.JAPANESE, "tokyo").ok);
        assertFalse(tokenizer.convert("東京", WakeLanguage.JAPANESE, "東京").ok);
        assertFalse(tokenizer.convert("東京", WakeLanguage.JAPANESE, "あ".repeat(41)).ok);
    }

    @Test
    public void japaneseWarningsCountMoraInsteadOfChineseCharacters() {
        var result = tokenizer.convert("キャー", WakeLanguage.JAPANESE, "");
        assertTrue(result.ok);
        assertEquals(WakeWordTokenizer.Err.JAPANESE_TOO_SHORT, result.err);
        assertEquals(2, result.errCount);
    }
}
