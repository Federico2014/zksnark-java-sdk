package org.tron.common.zksnark;

import java.util.Arrays;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class ProofFailureTest {
  private final Librustzcash nativeApi = LibrustzcashWrapper.getInstance();

  @BeforeClass
  public static void initParameters() {
    LibrustzcashTest.librustzcashInitZksnarkParams();
  }

  @Test
  public void invalidSpendInputsPreserveOutputsAndContext() {
    long context = nativeApi.librustzcashSaplingProvingCtxInit();
    try {
      for (int scenario = 0; scenario < 5; scenario++) {
        byte[] nsk = new byte[32];
        byte[] anchor = new byte[32];
        byte[] witness = new byte[1065];
        witness[0] = 32;
        for (int i = 0; i < 32; i++) {
          witness[1 + 33 * i] = 32;
        }
        switch (scenario) {
          case 0:
            Arrays.fill(nsk, (byte) 0xff);
            break;
          case 1:
            witness[0] = 31;
            break;
          case 2:
            witness[1] = 31;
            break;
          case 3:
            Arrays.fill(witness, 2, 34, (byte) 0xff);
            break;
          default:
            Arrays.fill(anchor, (byte) 0xff);
        }
        byte[] cv = filled(32);
        byte[] rk = filled(32);
        byte[] proof = filled(192);
        Assert.assertFalse(nativeApi.librustzcashSaplingSpendProof(context,
            hex("11b96c93a610b6cb04769f8df693d610b781374348b38939b3a109e317d0845d"),
            nsk, diversifier(), new byte[32], new byte[32], 0, anchor, witness,
            cv, rk, proof));
        Assert.assertArrayEquals(filled(32), cv);
        Assert.assertArrayEquals(filled(32), rk);
        Assert.assertArrayEquals(filled(192), proof);
        assertBindingSignature(context, 0);
      }

      // A failed Spend must not prevent subsequent successful work on this context.
      Assert.assertTrue(outputProof(context, diversifier(), new byte[32],
          new byte[32], new byte[192]));
      assertBindingSignature(context, -7);
    } finally {
      nativeApi.librustzcashSaplingProvingCtxFree(context);
    }
  }

  @Test
  public void failedOutputPreservesExistingProofAndContext() {
    long context = nativeApi.librustzcashSaplingProvingCtxInit();
    try {
      byte[] cv = new byte[32];
      byte[] proof = new byte[192];
      Assert.assertTrue(outputProof(context, diversifier(), new byte[32], cv, proof));
      byte[] savedCv = cv.clone();
      byte[] savedProof = proof.clone();
      assertBindingSignature(context, -7);

      byte[] invalidDiversifier = hex("55ed53f0d6550b472cdf38");
      Assert.assertFalse(nativeApi.librustzcashCheckDiversifier(invalidDiversifier));
      // The malformed diversifier reaches circuit synthesis and fails after RNG use.
      Assert.assertFalse(outputProof(context, invalidDiversifier, new byte[32], cv, proof));
      Assert.assertArrayEquals(savedCv, cv);
      Assert.assertArrayEquals(savedProof, proof);
      assertBindingSignature(context, -7);

      byte[] invalidScalar = new byte[32];
      Arrays.fill(invalidScalar, (byte) 0xff);
      Assert.assertFalse(outputProof(context, diversifier(), invalidScalar, cv, proof));
      Assert.assertArrayEquals(savedCv, cv);
      Assert.assertArrayEquals(savedProof, proof);
      assertBindingSignature(context, -7);

      Assert.assertTrue(outputProof(context, diversifier(), new byte[32], cv, proof));
      assertBindingSignature(context, -14);
    } finally {
      nativeApi.librustzcashSaplingProvingCtxFree(context);
    }
  }

  private boolean outputProof(long context, byte[] diversifier, byte[] randomness,
      byte[] cv, byte[] proof) {
    byte[] esk = new byte[32];
    esk[0] = 1;
    return nativeApi.librustzcashSaplingOutputProof(context, esk, diversifier,
        hex("4f05edbf1abe79773a46dbf16f43aa05290225487b656413533c405272b9bd5d"),
        randomness, 7, cv, proof);
  }

  private void assertBindingSignature(long context, long valueBalance) {
    Assert.assertTrue(nativeApi.librustzcashSaplingBindingSig(context, valueBalance,
        new byte[32], new byte[64]));
  }

  private static byte[] diversifier() {
    return hex("4c418cf65a7210d9b66588");
  }

  private static byte[] filled(int length) {
    byte[] result = new byte[length];
    Arrays.fill(result, (byte) 0x55);
    return result;
  }

  private static byte[] hex(String value) {
    byte[] result = new byte[value.length() / 2];
    for (int i = 0; i < result.length; i++) {
      result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
    }
    return result;
  }
}
