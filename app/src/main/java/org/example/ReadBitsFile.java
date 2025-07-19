package org.example;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ReadBitsFile {
  private InputStream input;
  private int currentByte;
  private int numBitsRemaining;
  private boolean isEndOfStream;
  private static long totalbitstoread;

  public ReadBitsFile(FileInputStream input, long len) throws IOException {
    this.input = input;
    numBitsRemaining = 0;
    isEndOfStream = false;
    System.out.println("pads cpuDec: " + cpuDecompression.pad);

    totalbitstoread = len * 8;
    totalbitstoread -= cpuDecompression.pad;
    totalbitstoread -= 8;
    System.out.println(totalbitstoread);
  }

  public void calibrateTotalbitstoread(int metasize) {
    totalbitstoread = totalbitstoread - (metasize * 8);
  }

  public int readByte() throws IOException {
    return input.read();
  }

  public byte[] readCode(int len) throws IOException {
    return input.readNBytes(len);
  }

  // Returns the next bit (0 or 1), or -1 if end of stream
  public int readBit() throws IOException {
    if (totalbitstoread == 0) {

      System.out.println("TotalbitsreachedEnd");
      return -1;
    }
    if (isEndOfStream) {
      return -1;
    }

    if (numBitsRemaining == 0) {
      currentByte = input.read();
      if (currentByte == -1) {
        isEndOfStream = true;
        System.out.println(totalbitstoread + "is left to read.");
        return -1;
      }
      numBitsRemaining = 8;
    }
    totalbitstoread--;
    numBitsRemaining--;
    return (currentByte >> numBitsRemaining) & 1;
  }

  public void close() throws IOException {
    input.close();
  }
}
