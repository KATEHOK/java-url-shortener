package io.github.katehok.shortener.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RandomCodeGeneratorTest {

    @Test
    void generatesCodeOfGivenLength() {
        CodeGenerator generator = CodeGenerator.random(7);
        assertEquals(7, generator.next().length());
    }

    @Test
    void usesOnlyBase62Chars() {
        CodeGenerator generator = CodeGenerator.random(new Random(42), 50);
        for (int i = 0; i < 100; ++i) {
            for (char ch : generator.next().toCharArray()) {
                assertTrue(CodeGenerator.ALPHABET.indexOf(ch) >= 0, "unexpected char " + ch);
            }
        }
    }

    @Test
    void generatesDifferentCodes() {
        CodeGenerator generator = CodeGenerator.random(7);
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; ++i) {
            codes.add(generator.next());
        }
        assertEquals(10_000, codes.size());
    }

    @Test
    void rejectsNonPositiveLength() {
        assertThrows(IllegalArgumentException.class, () -> CodeGenerator.random(0));
    }
}
