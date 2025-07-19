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
  private Map<String, Integer> codebook = new HashMap<>();

  cpuDecompression(String in, String out) throws IOException {
    ifile = new File(in);
    pad = getpad();
    System.out.println(pad);
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

    int codeBookSize = inFile.readByte();
    metaDataSize++;
    for (int i = 0; i < codeBookSize; i++) {
      int symbol = inFile.readByte();
      metaDataSize++;
      int codelen = inFile.readByte();
      metaDataSize++;
      String codes = new String(inFile.readCode(codelen));
      metaDataSize = metaDataSize + codelen;
      codebook.put(codes, symbol);
    }

    inFile.calibrateTotalbitstoread(metaDataSize);

    int inputby;
    String key = "";
    while ((inputby = inFile.readBit()) != -1) {
      key += inputby;
      if (codebook.containsKey(key)) {
        outFile.write(codebook.get(key));
        key = "";
      }
    }

  }

  public void close() throws IOException {
    inFile.close();
    outFile.close();
  }

}
