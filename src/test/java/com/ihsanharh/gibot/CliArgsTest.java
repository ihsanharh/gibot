package com.ihsanharh.gibot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CliArgsTest {
    @Test
    void parsesAuthFlagAndLoginMode() {
        GiBot.CliOptions opts = GiBot.parseCliArgs(new String[]{"login", "-a", "accounts/gift01.json", "-j"});
        assertEquals("accounts/gift01.json", opts.authPath);
        assertTrue(opts.jsonOutput);
        assertArrayEquals(new String[]{"login"}, opts.cleanArgs);
    }

    @Test
    void parsesEqualsAuthVariants() {
        GiBot.CliOptions optsLong = GiBot.parseCliArgs(new String[]{"gift", "Player1", "Hive+", "--auth=/tmp/acc1.json"});
        assertEquals("/tmp/acc1.json", optsLong.authPath);
        assertArrayEquals(new String[]{"gift", "Player1", "Hive+"}, optsLong.cleanArgs);

        GiBot.CliOptions optsShort = GiBot.parseCliArgs(new String[]{"tokens", "-a=accounts/a2.json"});
        assertEquals("accounts/a2.json", optsShort.authPath);
        assertArrayEquals(new String[]{"tokens"}, optsShort.cleanArgs);
    }

    @Test
    void defaultsAuthPathToAuthJson() {
        GiBot.CliOptions opts = GiBot.parseCliArgs(new String[]{"tokens", "--json"});
        assertEquals("auth.json", opts.authPath);
        assertTrue(opts.jsonOutput);
        assertArrayEquals(new String[]{"tokens"}, opts.cleanArgs);
    }
}
