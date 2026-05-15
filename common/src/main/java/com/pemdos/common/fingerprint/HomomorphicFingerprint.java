package com.pemdos.common.fingerprint;

import java.security.SecureRandom;

public class HomomorphicFingerprint {

    private final int dataShards;
    private final int[][] encodingMatrix; // [totalShards][dataShards]

    public HomomorphicFingerprint(int dataShards, int parityShards) {
        this.dataShards = dataShards;
        this.encodingMatrix = buildEncodingMatrix(dataShards, dataShards + parityShards);
    }

    // Pick a non-zero random point in GF(2^8) to use as the evaluation point.
    // This is chosen once per stored object and kept secret alongside the metadata.
    public int randomEvalPoint() {
        return new SecureRandom().nextInt(255) + 1; // range [1, 255]
    }

    // Treat the byte array as a polynomial and evaluate it at point r in GF(2^8).
    //
    //   fp = data[0]  +  data[1]*r  +  data[2]*r^2  +  ...  +  data[n-1]*r^(n-1)
    //
    // All additions are XOR; all multiplications use the GaloisField log/exp tables.
    public static int evaluate(byte[] data, int r) {
        int result = 0;
        int rPow = 1; // tracks r^i; starts at r^0 = 1
        for (byte b : data) {
            result = GaloisField.add(result, GaloisField.multiply(b & 0xff, rPow));
            rPow = GaloisField.multiply(rPow, r);
        }
        return result;
    }

    public int[] computeDataFingerprints(byte[][] dataShardArrays, int r) {
        int[] fps = new int[dataShards];
        for (int i = 0; i < dataShards; i++) {
            fps[i] = evaluate(dataShardArrays[i], r);
        }
        return fps;
    }

    // Compute the expected fingerprint of encoded shard j using the encoding matrix.
    //
    //   expected_fp[j] = M[j][0]*fp[0]  +  M[j][1]*fp[1]  +  ...  +  M[j][k-1]*fp[k-1]
    //
    // This works because RS encoding is linear: fp(encode_j(D)) = encode_j(fp(D)).
    // The coordinator computes this from the data shard fingerprints at upload time
    // and stores it. At retrieval, it recomputes fp from the actual shard bytes and
    // checks they match - without needing the other shards.
    public int expectedFingerprint(int[] dataFingerprints, int shardIndex) {
        int result = 0;
        for (int i = 0; i < dataShards; i++) {
            result = GaloisField.add(result,
                    GaloisField.multiply(encodingMatrix[shardIndex][i], dataFingerprints[i]));
        }
        return result;
    }

    public boolean verify(byte[] shard, int shardIndex, int[] dataFingerprints, int r) {
        int actual = evaluate(shard, r);
        int expected = expectedFingerprint(dataFingerprints, shardIndex);
        return actual == expected;
    }

    // -------------------------------------------------------------------------
    // Encoding matrix construction (Vandermonde, made systematic)
    // -------------------------------------------------------------------------

    // Build the systematic encoding matrix used by the Reed-Solomon code.
    //
    // Step 1: Construct a Vandermonde matrix V where V[r][c] = r^c in GF(2^8).
    //         This gives a totalShards × dataShards matrix.
    //
    // Step 2: Extract the top dataShards × dataShards submatrix (call it T).
    //         Invert T using Gaussian elimination over GF(2^8).
    //
    // Step 3: M = V * T^(-1).
    //         The top dataShards rows of M form an identity matrix (systematic form),
    //         and the bottom parityShards rows are the parity coefficients.
    //
    // This is the same construction Backblaze JavaReedSolomon uses internally.
    private static int[][] buildEncodingMatrix(int dataShards, int totalShards) {
        int[][] vander = new int[totalShards][dataShards];
        for (int r = 0; r < totalShards; r++) {
            for (int c = 0; c < dataShards; c++) {
                vander[r][c] = GaloisField.pow(r, c);
            }
        }

        int[][] top = new int[dataShards][dataShards];
        for (int r = 0; r < dataShards; r++) {
            System.arraycopy(vander[r], 0, top[r], 0, dataShards);
        }

        return multiplyMatrices(vander, invertMatrix(top));
    }

    private static int[][] multiplyMatrices(int[][] a, int[][] b) {
        int rows = a.length;
        int inner = b.length;
        int cols = b[0].length;
        int[][] result = new int[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                int sum = 0;
                for (int k = 0; k < inner; k++) {
                    sum = GaloisField.add(sum, GaloisField.multiply(a[i][k], b[k][j]));
                }
                result[i][j] = sum;
            }
        }
        return result;
    }

    // Invert a square matrix over GF(2^8) using Gauss-Jordan elimination.
    //
    // We augment the matrix with an identity [M | I] and apply row operations
    // until the left half becomes the identity, leaving [I | M^(-1)] on the right.
    // Every scalar operation (division, subtraction) uses GaloisField arithmetic.
    private static int[][] invertMatrix(int[][] matrix) {
        int n = matrix.length;
        int[][] aug = new int[n][2 * n];

        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, aug[i], 0, n);
            aug[i][n + i] = 1; // identity on the right
        }

        for (int col = 0; col < n; col++) {
            // Find a non-zero pivot in this column
            if (aug[col][col] == 0) {
                for (int row = col + 1; row < n; row++) {
                    if (aug[row][col] != 0) {
                        int[] tmp = aug[col];
                        aug[col] = aug[row];
                        aug[row] = tmp;
                        break;
                    }
                }
            }
            // Scale the pivot row so the pivot element becomes 1
            int pivotInv = GaloisField.inverse(aug[col][col]);
            for (int j = 0; j < 2 * n; j++) {
                aug[col][j] = GaloisField.multiply(aug[col][j], pivotInv);
            }
            // Zero out every other row in this column
            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                int factor = aug[row][col];
                for (int j = 0; j < 2 * n; j++) {
                    aug[row][j] = GaloisField.add(aug[row][j],
                            GaloisField.multiply(factor, aug[col][j]));
                }
            }
        }

        int[][] result = new int[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(aug[i], n, result[i], 0, n);
        }
        return result;
    }
}
