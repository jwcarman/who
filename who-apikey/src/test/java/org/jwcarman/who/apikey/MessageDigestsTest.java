/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.who.apikey;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.jwcarman.who.apikey.MessageDigests.SHA_256;

class MessageDigestsTest {

    // Known SHA-256 of "hello" (hex)
    private static final String HELLO_SHA256_HEX =
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @Test
    void sha256ProducesCorrectDigest() {
        byte[] result = MessageDigests.sha256("hello".getBytes(StandardCharsets.UTF_8));

        assertThat(result).asHexString().isEqualToIgnoringCase(HELLO_SHA256_HEX);
    }

    @Test
    void sha256ProducesTwentyFourByteDigest() {
        byte[] result = MessageDigests.sha256("test".getBytes(StandardCharsets.UTF_8));

        assertThat(result).hasSize(32);
    }

    @Test
    void messageDigestWithSha256AlgorithmConstantMatchesSha256() {
        byte[] input = "consistency".getBytes(StandardCharsets.UTF_8);

        assertThat(MessageDigests.messageDigest(SHA_256, input))
                .isEqualTo(MessageDigests.sha256(input));
    }

    @Test
    void messageDigestThrowsForInvalidAlgorithm() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MessageDigests.messageDigest("NOT-A-REAL-ALGO", new byte[]{1, 2, 3}))
                .withMessageContaining("NOT-A-REAL-ALGO");
    }
}
