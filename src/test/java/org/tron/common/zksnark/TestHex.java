package org.tron.common.zksnark;

/** Decodes hexadecimal test vectors without depending on JDK internal APIs. */
final class TestHex {
  private TestHex() {}

  static byte[] decode(String value) {
    if ((value.length() & 1) != 0) {
      throw new IllegalArgumentException("Hexadecimal input must have an even length");
    }
    byte[] result = new byte[value.length() / 2];
    for (int i = 0; i < value.length(); i += 2) {
      char highChar = value.charAt(i);
      char lowChar = value.charAt(i + 1);
      int high = Character.digit(highChar, 16);
      int low = Character.digit(lowChar, 16);
      if (highChar > 127 || lowChar > 127 || high < 0 || low < 0) {
        throw new IllegalArgumentException("Invalid hexadecimal input at byte " + i / 2);
      }
      result[i / 2] = (byte) ((high << 4) | low);
    }
    return result;
  }
}
