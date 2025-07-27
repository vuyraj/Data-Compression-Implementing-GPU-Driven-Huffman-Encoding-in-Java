package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class canonicalCode {

  public static Map<Integer, String> canonicalCodeBook = new HashMap<>();

  public static void canonicalCodeConversion(int[] length) {

    List<int[]> codeLength = new ArrayList<>();
    // appending the code in list
    for (int i = 0; i < 256; i++) {
      int len = length[i];
      if (len > 0) {
        codeLength.add(new int[] { i, len });
      }

    }

    // sorting the list
    codeLength.sort((a, b) -> {
      if (a[1] != b[1]) {
        return Integer.compare(a[1], b[1]);
      }
      return Integer.compare(a[0], b[0]);
    });

    // Canonical code creation

    int code = 0;
    int pvlen = 0;

    for (int[] entry : codeLength) {

      int symb = entry[0];
      int clen = entry[1];

      // only for length grater than 0
      if (clen > pvlen) {
        code <<= (clen - pvlen);
        pvlen = clen;
      }

      String codeStr = String.format("%" + clen + "s", Integer.toBinaryString(code)).replace(' ', '0');

      canonicalCodeBook.put(symb, codeStr);

      code++;
    }

  }
}
