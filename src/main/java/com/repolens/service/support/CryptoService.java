package com.repolens.service.support;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AppSettingEntity;
import com.repolens.mapper.AppSettingMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption service.
 *
 * <p>Key resolution order:
 * <ol>
 *   <li>Environment variable {@code REPOLENS_CRYPTO_KEY} (base64-encoded 32 bytes)</li>
 *   <li>Persisted value in {@code app_setting} (k = {@code crypto.master-key})</li>
 *   <li>Auto-generated 32-byte key stored in {@code app_setting} on first use</li>
 * </ol>
 *
 * <p>Each call to {@link #encrypt(String)} generates a fresh 12-byte random IV.
 * Output format: base64(iv || ciphertext||tag), where the GCM authentication tag
 * is appended by the JCE implementation as part of the ciphertext.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String SETTING_KEY = "crypto.master-key";

    @Value("${REPOLENS_CRYPTO_KEY:}")
    private String envKey;

    private final AppSettingMapper appSettingMapper;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes;
        if (envKey != null && !envKey.isBlank()) {
            keyBytes = Base64.getDecoder().decode(envKey.trim());
            log.info("[Crypto] Loaded AES key from environment variable.");
        } else {
            AppSettingEntity existing = appSettingMapper.selectById(SETTING_KEY);
            if (existing != null && existing.getV() != null && !existing.getV().isBlank()) {
                keyBytes = Base64.getDecoder().decode(existing.getV());
                log.info("[Crypto] Loaded AES key from app_setting.");
            } else {
                keyBytes = generateKey();
                AppSettingEntity entity = new AppSettingEntity();
                entity.setK(SETTING_KEY);
                entity.setV(Base64.getEncoder().encodeToString(keyBytes));
                appSettingMapper.insert(entity);
                log.info("[Crypto] Generated and stored new AES key in app_setting.");
            }
        }
        if (keyBytes.length != 32) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "Crypto key must be exactly 32 bytes (256 bits).");
        }
        secretKey = new SecretKeySpec(keyBytes, "AES");
        Arrays.fill(keyBytes, (byte) 0); // wipe from heap
    }

    /**
     * Encrypts the given plaintext using AES-256-GCM with a fresh random IV.
     *
     * @param plaintext the value to encrypt
     * @return base64(iv || gcm-ciphertext-with-tag)
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, combined, IV_BYTES, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "Encryption failed: " + e.getMessage());
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt(String)}.
     *
     * @param encoded base64(iv || gcm-ciphertext-with-tag)
     * @return original plaintext
     * @throws BizException with {@link ErrorCode#SYSTEM_ERROR} if decryption fails
     */
    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length < IV_BYTES) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, "Decryption failed: data too short.");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plainBytes = cipher.doFinal(ciphertext);
            return new String(plainBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "Decryption failed: " + e.getMessage());
        }
    }

    private byte[] generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
