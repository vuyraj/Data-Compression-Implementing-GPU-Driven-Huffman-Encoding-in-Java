package org.example;

import java.io.FileInputStream;
import java.util.Map;

public class cpuCompression {
  private static Map<Integer, String> codebook = canonicalCode.canonicalCodeBook;

  public static void compress(String inFileName, String outFileName) {
    int inp;
    try {
      FileInputStream fd = new FileInputStream(inFileName);
      WriteBitsFile wbf = new WriteBitsFile(outFileName);

      // write length to the compressed file
      for (int i : cpuHuffman.codeLength) {

        wbf.writeInt(i);

      }

      // encode the file
      while ((inp = fd.read()) != -1) {
        String code = codebook.get(inp);
        wbf.writeCode(code);
      }

      wbf.close();

      fd.close();

    } catch (Exception e) {
      System.out.println(e);
    }

  }
}
