package com.pemdos.common.fingerprint;

public class GaloisField {

    // Primitive polynomial: x^8 + x^4 + x^3 + x^2 + 1 = 0x11d.
    // This defines the "modulus" for field arithmetic - the same polynomial
    // the Backblaze Reed-Solomon library uses.
    private static final int PRIMITIVE_POLY = 0x11d;

    // Extended to 512 entries so we can index with (LOG[a] + LOG[b]) directly
    // without an explicit modulo on every multiply.
    static final int[] EXP = new int[512];

    // LOG[x] = i  such that alpha^i = x.
    // LOG[0] is undefined - we never look it up (handled by the 0-check in multiply).
    static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            // Multiply x by alpha (= 2): left-shift by 1.
            // If bit 8 would be set, reduce modulo the primitive polynomial.
            x <<= 1;
            if ((x & 0x100) != 0) {
                x ^= PRIMITIVE_POLY;
            }
            x &= 0xff;
        }
        // The multiplicative group has order 255, so alpha^255 = alpha^0 = 1.
        // Fill the second half of EXP so index (i + j) never wraps past 510.
        for (int i = 255; i < 512; i++) {
            EXP[i] = EXP[i - 255];
        }
    }

    // Addition in GF(2^8) is bitwise XOR - there are no carries.
    public static int add(int a, int b) {
        return a ^ b;
    }

    // Multiplication using the log/exp identity: a*b = alpha^(log[a] + log[b]).
    // The extended EXP table lets us skip the mod-255 step.
    public static int multiply(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    // a^n in GF(2^8).
    // Special case: anything^0 = 1, 0^n = 0 for n > 0.
    public static int pow(int a, int n) {
        if (n == 0) return 1;
        if (a == 0) return 0;
        return EXP[(LOG[a] * n) % 255];
    }

    // Multiplicative inverse: a^(-1) = alpha^(255 - log[a]).
    public static int inverse(int a) {
        if (a == 0) throw new ArithmeticException("0 has no multiplicative inverse in GF(2^8)");
        return EXP[255 - LOG[a]];
    }
}
