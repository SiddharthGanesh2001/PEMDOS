package com.pemdos.common.codec;

import com.backblaze.erasure.ReedSolomon;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ReedSolomonCodec {

    private final int dataShards;
    private final int parityShards;
    private final int totalShards;

    public ReedSolomonCodec(int dataShards, int parityShards) {
        this.dataShards = dataShards;
        this.parityShards = parityShards;
        this.totalShards = dataShards + parityShards;
    }

    public byte[][] encode(byte[] data) {
        // Each data shard must be the same size. We pad the input to the nearest
        // multiple of dataShards, then prepend a 4-byte int storing the original size
        // so we can strip the padding on decode.
        int storedSize = data.length;
        int shardSize = (storedSize + dataShards - 1) / dataShards;

        byte[] padded = Arrays.copyOf(data, shardSize * dataShards);

        byte[][] shards = new byte[totalShards][shardSize];

        // Split padded data evenly across the data shards
        for (int i = 0; i < dataShards; i++) {
            System.arraycopy(padded, i * shardSize, shards[i], 0, shardSize);
        }

        // The Backblaze library fills in the parity shards in-place
        ReedSolomon codec = ReedSolomon.create(dataShards, parityShards);
        codec.encodeParity(shards, 0, shardSize);

        return shards;
    }

    public byte[] decode(byte[][] shards, boolean[] shardPresent, int originalSize) {
        int shardSize = shards[0] != null ? shards[0].length
                : findFirstPresent(shards, shardPresent).length;

        // Fill missing shards with zero-byte arrays so the library can reconstruct them
        for (int i = 0; i < totalShards; i++) {
            if (!shardPresent[i]) {
                shards[i] = new byte[shardSize];
            }
        }

        ReedSolomon codec = ReedSolomon.create(dataShards, parityShards);
        codec.decodeMissing(shards, shardPresent, 0, shardSize);

        // Reassemble the data shards and trim to the original size
        byte[] reconstructed = new byte[dataShards * shardSize];
        for (int i = 0; i < dataShards; i++) {
            System.arraycopy(shards[i], 0, reconstructed, i * shardSize, shardSize);
        }

        return Arrays.copyOf(reconstructed, originalSize);
    }

    public boolean canReconstruct(boolean[] shardPresent) {
        int available = 0;
        for (boolean present : shardPresent) {
            if (present) available++;
        }
        return available >= dataShards;
    }

    private byte[] findFirstPresent(byte[][] shards, boolean[] shardPresent) {
        for (int i = 0; i < totalShards; i++) {
            if (shardPresent[i] && shards[i] != null) {
                return shards[i];
            }
        }
        throw new IllegalStateException("No shards present");
    }
}
