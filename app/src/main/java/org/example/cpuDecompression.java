package org.example;

import java.io.FileOutputStream;
import java.io.IOException;

public class cpuDecompression {

  private ReadBitsFile inFile;
  private FileOutputStream outFile;

  cpuDecompression(String in, String out) throws IOException {
    inFile = new ReadBitsFile(in);
    outFile = new FileOutputStream(out);
  }

  public void decompress() throws IOException {

    int input;
    String key = "";
    while ((input = inFile.readBit()) != -1) {
      key += input;
      if (cpuHuffman.decodebook.containsKey(key)) {
        outFile.write(cpuHuffman.decodebook.get(key));
        key = "";
      }
    }

  }

  public void close() throws IOException {
    inFile.close();
    outFile.close();
  }

}
