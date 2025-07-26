package org.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

public class cpuDecompression {

  public static int pad;
  public static int metaDataSize = 0;

  private File ifile;
  private ReadBitsFile inFile;
  private FileOutputStream outFile;
  private Map<String, Integer> deCodebook = new HashMap<>();

  cpuDecompression(String in, String out) throws IOException {
    ifile = new File(in);
    pad = getpad();
    inFile = new ReadBitsFile(new FileInputStream(in), ifile.length());
    outFile = new FileOutputStream(out);
  }

  public int getpad() {
    int p = 0;
    try {
      RandomAccessFile raf = new RandomAccessFile(ifile, "rw");
      raf.seek(ifile.length() - 1);
      p = raf.read();
      raf.close();
    } catch (Exception e) {
      System.out.println("Error while reading pad");
    }
    return p;
  }

  public void decompress() throws IOException {

    int[] codelen = new int[256];
    for (int i = 0; i < 256; i++) {
      codelen[i] = inFile.readByte();
    }

    // clear the codebook so that codeook will not be affected because of previous
    // data.
    canonicalCode.canonicalCodeBook.clear();

    canonicalCode.canonicalCodeConversion(codelen);

    for (Map.Entry<Integer, String> canCode : canonicalCode.canonicalCodeBook.entrySet()) {
      deCodebook.put(canCode.getValue(), canCode.getKey());
    }

    System.out.println("Decompressed codebook");

    metaDataSize = 256;
    inFile.calibrateTotalbitstoread(metaDataSize);

    int inputby;
    StringBuilder keybuild = new StringBuilder();
    String key = "";
    while ((inputby = inFile.readBit()) != -1) {
      keybuild.append(inputby);
      key = keybuild.toString();
      if (deCodebook.containsKey(key)) {
        outFile.write(deCodebook.get(key));
        keybuild.setLength(0);
      }
    }

  }

  public void close() throws IOException {
    inFile.close();
    outFile.close();
  }

}
