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
}
