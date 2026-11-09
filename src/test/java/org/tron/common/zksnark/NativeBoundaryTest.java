package org.tron.common.zksnark;

import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public class NativeBoundaryTest {
  private final Librustzcash nativeApi = LibrustzcashWrapper.getInstance();

  @Test
  public void identitySpendKeysReturnFalse() {
    for (int sign : new int[] {0, 0x80}) {
      byte[] ak = new byte[32];
      ak[0] = 1;
      ak[31] = (byte) sign;
      byte[] proof = filled(192);
      long context = nativeApi.librustzcashSaplingProvingCtxInit();
      try {
        Assert.assertFalse(nativeApi.librustzcashSaplingSpendProof(context, ak,
            new byte[32], new byte[11], new byte[32], new byte[32], 0,
            new byte[32], new byte[1065], new byte[32], new byte[32], proof));
        Assert.assertArrayEquals(filled(192), proof);
      } finally {
        nativeApi.librustzcashSaplingProvingCtxFree(context);
      }
    }
  }

  @Test
  public void checkedNullifierPreservesKnownVectorAndLegacyApi() {
    byte[] result = new byte[32];
    Assert.assertTrue(computeNullifier(rcm(), result));
    byte[] expected = hex("7f22468cae810da22743f8f66278b690a729c70aa0ee88d6736d43b5ea29ddf1");
    Assert.assertArrayEquals(expected, result);
    Arrays.fill(result, (byte) 0);
    nativeApi.librustzcashSaplingComputeNf(diversifier(), pkd(), 99990000, rcm(),
        ak(), nk(), 10, result);
    Assert.assertArrayEquals(expected, result);
  }

  @Test
  public void checkedNullifierReportsInvalidInputsWithoutWritingOutput() {
    byte[] result = filled(32);
    byte[] invalidScalar = new byte[32];
    Arrays.fill(invalidScalar, (byte) 0xff);
    Assert.assertFalse(computeNullifier(invalidScalar, result));
    Assert.assertFalse(computeNullifier(null, result));
    Assert.assertFalse(computeNullifier(new byte[31], result));
    Assert.assertFalse(nativeApi.librustzcashSaplingComputeNfChecked(diversifier(),
        invalidScalar, 1, rcm(), ak(), nk(), 0, result));
    Assert.assertFalse(nativeApi.librustzcashSaplingComputeNfChecked(diversifier(),
        pkd(), -1, rcm(), ak(), nk(), 0, result));
    Assert.assertArrayEquals(filled(32), result);
  }

  @Test
  public void zip32SupportsSeedPrefixesEmptySeedsAndHardenedChildren() {
    byte[] master = new byte[169];
    byte[] prefixMaster = new byte[169];
    nativeApi.librustzcashZip32XskMaster(new byte[32], 16, master);
    nativeApi.librustzcashZip32XskMaster(new byte[16], 16, prefixMaster);
    Assert.assertArrayEquals(master, prefixMaster);
    nativeApi.librustzcashZip32XskMaster(new byte[0], 0, master);
    byte[] child = new byte[169];
    Assert.assertTrue(nativeApi.librustzcashZip32XskDeriveChecked(master,
        0x80000000, child));
    byte[] legacyChild = new byte[169];
    nativeApi.librustzcashZip32XskDerive(master, 0x80000000, legacyChild);
    Assert.assertArrayEquals(child, legacyChild);
    Assert.assertEquals(1, child[0]);
  }

  @Test
  public void zip32RejectsMalformedKeysLengthsAndExhaustedDepth() {
    byte[] key = new byte[169];
    Arrays.fill(key, (byte) 0xff);
    key[0] = 0;
    byte[] output = filled(169);
    Assert.assertFalse(nativeApi.librustzcashZip32XskDeriveChecked(key, 0, output));
    Assert.assertThrows(IllegalArgumentException.class,
        () -> nativeApi.librustzcashZip32XskDerive(key, 0, output));
    Assert.assertFalse(nativeApi.librustzcashZip32XskDeriveChecked(null, 0, output));
    Assert.assertFalse(nativeApi.librustzcashZip32XskDeriveChecked(new byte[168], 0,
        output));
    Assert.assertFalse(nativeApi.librustzcashZip32XfvkAddress(key, new byte[11],
        new byte[11], new byte[43]));
    Assert.assertFalse(nativeApi.librustzcashZip32XfvkAddress(key, new byte[10],
        new byte[11], new byte[43]));
    nativeApi.librustzcashZip32XskMaster(new byte[32], 32, key);
    key[0] = (byte) 255;
    Assert.assertFalse(nativeApi.librustzcashZip32XskDeriveChecked(key, 0, output));
    Assert.assertArrayEquals(filled(169), output);
  }

  @Test
  public void zip32RejectsInvalidSeedBounds() {
    Assert.assertThrows(IllegalArgumentException.class,
        () -> nativeApi.librustzcashZip32XskMaster(null, 0, new byte[169]));
    Assert.assertThrows(IllegalArgumentException.class,
        () -> nativeApi.librustzcashZip32XskMaster(new byte[32], -1, new byte[169]));
    Assert.assertThrows(IllegalArgumentException.class,
        () -> nativeApi.librustzcashZip32XskMaster(new byte[32], 33, new byte[169]));
    Assert.assertThrows(IllegalArgumentException.class,
        () -> nativeApi.librustzcashZip32XskMaster(new byte[32], 32, new byte[168]));
  }

  private boolean computeNullifier(byte[] randomness, byte[] result) {
    return nativeApi.librustzcashSaplingComputeNfChecked(diversifier(), pkd(), 99990000,
        randomness, ak(), nk(), 10, result);
  }

  private static byte[] diversifier() {
    return hex("4c418cf65a7210d9b66588");
  }

  private static byte[] pkd() {
    return hex("4f05edbf1abe79773a46dbf16f43aa05290225487b656413533c405272b9bd5d");
  }

  private static byte[] rcm() {
    return hex("0e6eb90683c2a3e2d6477c68766e19e2376d24802684e1e69bf15ef074b3c206");
  }

  private static byte[] ak() {
    return hex("11b96c93a610b6cb04769f8df693d610b781374348b38939b3a109e317d0845d");
  }

  private static byte[] nk() {
    return hex("be75a89935512d029fddcf7b93466a417deea17ceb641e8372731a7367ea0464");
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
