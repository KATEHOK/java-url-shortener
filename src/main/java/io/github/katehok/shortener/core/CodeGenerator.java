package io.github.katehok.shortener.core;

import java.security.SecureRandom;
import java.util.Random;

public interface CodeGenerator {

    String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    String next();

    static CodeGenerator random(Random random, int length) {
        return new RandomCodeGenerator(random, length);
    }

    static CodeGenerator random(int length) {
        return random(new SecureRandom(), length);
    }
}

// Беру SecureRandom, а не хэш от url - так у одного url разные коды, и следующий код не угадать
class RandomCodeGenerator implements CodeGenerator {

    private final Random random;
    private final int length;

    RandomCodeGenerator(Random random, int length) {
        if (length < 1) throw new IllegalArgumentException("Code length must be positive");
        this.random = random;
        this.length = length;
    }

    @Override
    public String next() {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; ++i) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
