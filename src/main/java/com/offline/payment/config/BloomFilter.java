package com.offline.payment.config;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.BitSet;

@Component
public class BloomFilter {

    private final BitSet bits;
    private final int numHashFunctions;
    private final int bitArraySize;

    public BloomFilter() {
        this(20_000_000, 0.001);
    }

    public BloomFilter(long expectedInsertions, double falsePositiveRate) {
        this.bitArraySize = optimalBitArraySize(expectedInsertions, falsePositiveRate);
        this.numHashFunctions = optimalNumHashFunctions(expectedInsertions, bitArraySize);
        this.bits = new BitSet(bitArraySize);
    }

    public void add(byte[] element) {
        long[] hashes = hash(element);
        for (int i = 0; i < numHashFunctions; i++) {
            int idx = (int) (hashes[i] % bitArraySize);
            bits.set(idx);
        }
    }

    public boolean mightContain(byte[] element) {
        long[] hashes = hash(element);
        for (int i = 0; i < numHashFunctions; i++) {
            int idx = (int) (hashes[i] % bitArraySize);
            if (!bits.get(idx)) return false;
        }
        return true;
    }

    private long[] hash(byte[] element) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(element);
            long[] result = new long[numHashFunctions];
            ByteBuffer bb = ByteBuffer.wrap(digest);
            long h1 = bb.getLong();
            long h2 = bb.getLong();
            for (int i = 0; i < numHashFunctions; i++) {
                result[i] = (h1 + i * h2) & Long.MAX_VALUE;
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int optimalBitArraySize(long n, double p) {
        return (int) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2)));
    }

    private static int optimalNumHashFunctions(long n, int m) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }
}
