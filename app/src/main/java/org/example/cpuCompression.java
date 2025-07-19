package org.example;

import java.io.FileInputStream;
import java.util.Map;

public class cpuCompression {

  public static void compress(String inFileName, String outFileName) {
    int inp;
    try {
      FileInputStream fd = new FileInputStream(inFileName);
      WriteBitsFile wbf = new WriteBitsFile(outFileName);

      wbf.writeInt(cpuHuffman.codebook.size());

      for (Map.Entry<Integer, String> entry : cpuHuffman.codebook.entrySet()) {
        int symbol = entry.getKey();
        String code = entry.getValue();

        wbf.writeInt(symbol);
        wbf.writeInt(code.length());
        for (char c : code.toCharArray()) {
          wbf.writeCodeInByte(c);
        }
      }

      while ((inp = fd.read()) != -1) {
        String code = cpuHuffman.codebook.get(inp);
        wbf.writeCode(code);
      }

      wbf.close();

      fd.close();

    } catch (Exception e) {
      System.out.println(e);
    }

  }
}
