package com.pemdos.common.fingerprint;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class FingerprintService {

    public String computeFingerprint(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public boolean verifyFingerprint(byte[] data, String expectedFingerprint) {
        return computeFingerprint(data).equals(expectedFingerprint);
    }

    public String[] computeShardFingerprints(byte[][] shards) {
        String[] fingerprints = new String[shards.length];
        for (int i = 0; i < shards.length; i++) {
            fingerprints[i] = computeFingerprint(shards[i]);
        }
        return fingerprints;
    }

    public String computeCombinedFingerprint(String[] shardFingerprints) {
        StringBuilder combined = new StringBuilder();
        for (String fp : shardFingerprints) {
            combined.append(fp);
        }
        return computeFingerprint(combined.toString().getBytes());
    }
}
