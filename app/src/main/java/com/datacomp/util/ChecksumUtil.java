package com.datacomp.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility for computing checksums.
 */
public class ChecksumUtil {
    
    public static MessageDigest createSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    public static byte[] computeSha256(byte[] data) {
        return computeSha256(data, 0, data.length);
    }
    
    public static byte[] computeSha256(byte[] data, int offset, int length) {
        MessageDigest digest = createSha256();
        digest.update(data, offset, length);
        return digest.digest();
    }
    
    public static String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

