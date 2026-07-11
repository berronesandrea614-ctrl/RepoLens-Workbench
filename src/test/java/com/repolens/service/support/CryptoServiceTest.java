package com.repolens.service.support;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AppSettingEntity;
import com.repolens.mapper.AppSettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CryptoService}.
 *
 * <p>The service is instantiated with a deterministic 32-byte key injected via
 * {@code REPOLENS_CRYPTO_KEY} (set through {@link ReflectionTestUtils}),
 * so no live database or Spring context is required.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CryptoServiceTest {

    /** Deterministic 32-byte key (base64-encoded) used by all tests. */
    private static final String TEST_KEY_B64 =
            Base64.getEncoder().encodeToString(new byte[32]); // 32 zero-bytes

    @Mock
    private AppSettingMapper appSettingMapper;

    private CryptoService cryptoService;

    @BeforeEach
    void setUp() {
        // Stub mapper so the key-generation path never hits the DB
        when(appSettingMapper.selectById(any())).thenReturn(null);
        when(appSettingMapper.insert(any(AppSettingEntity.class))).thenReturn(1);

        cryptoService = new CryptoService(appSettingMapper);
        // Inject deterministic key via the env-variable field
        ReflectionTestUtils.setField(cryptoService, "envKey", TEST_KEY_B64);
        cryptoService.init();
    }

    // -----------------------------------------------------------------------
    // Round-trip: encrypt then decrypt recovers plaintext
    // -----------------------------------------------------------------------

    @Test
    void encryptThenDecrypt_returnsOriginalPlaintext() {
        String plaintext = "super-secret-app-secret-value";

        String encoded = cryptoService.encrypt(plaintext);
        String decoded = cryptoService.decrypt(encoded);

        assertThat(decoded).isEqualTo(plaintext);
    }

    @Test
    void encryptThenDecrypt_emptyString_returnsEmpty() {
        String encoded = cryptoService.encrypt("");
        String decoded = cryptoService.decrypt(encoded);

        assertThat(decoded).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Probabilistic IV: same plaintext produces different ciphertexts
    // -----------------------------------------------------------------------

    @Test
    void encrypt_samePlaintext_twiceDifferentCiphertexts() {
        String plaintext = "hello feishu";

        String enc1 = cryptoService.encrypt(plaintext);
        String enc2 = cryptoService.encrypt(plaintext);

        assertThat(enc1).isNotEqualTo(enc2);
    }

    // -----------------------------------------------------------------------
    // Tamper detection: GCM authentication tag catches modification
    // -----------------------------------------------------------------------

    @Test
    void decrypt_tamperedCiphertext_throwsBizException() {
        String encoded = cryptoService.encrypt("sensitive data");
        byte[] raw = Base64.getDecoder().decode(encoded);

        // Flip a byte in the ciphertext portion (after the 12-byte IV)
        raw[13] ^= 0xFF;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cryptoService.decrypt(tampered))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(ErrorCode.SYSTEM_ERROR.getCode()));
    }

    @Test
    void decrypt_completelyGarbage_throwsBizException() {
        assertThatThrownBy(() -> cryptoService.decrypt("not-valid-base64!!!"))
                .isInstanceOf(BizException.class);
    }

    // -----------------------------------------------------------------------
    // Ciphertext must not contain the plaintext as a substring
    // -----------------------------------------------------------------------

    @Test
    void encrypt_ciphertextDoesNotContainPlaintext() {
        String plaintext = "my-feishu-app-secret-12345";

        String encoded = cryptoService.encrypt(plaintext);

        assertThat(encoded).doesNotContain(plaintext);
    }

    // -----------------------------------------------------------------------
    // Output is valid base64
    // -----------------------------------------------------------------------

    @Test
    void encrypt_outputIsValidBase64() {
        String encoded = cryptoService.encrypt("test value");

        // Base64 decode must not throw
        byte[] decoded = Base64.getDecoder().decode(encoded);
        // iv(12) + at least 1 byte plaintext + 16 byte GCM tag = >= 29 bytes
        assertThat(decoded.length).isGreaterThanOrEqualTo(29);
    }
}
