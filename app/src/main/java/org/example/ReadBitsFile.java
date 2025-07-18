package org.example;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ReadBitsFile {
  private InputStream input;
  private int currentByte;
  private int numBitsRemaining;
  private boolean isEndOfStream;
  private long totalbitstoread;

  public ReadBitsFile(String filename) throws IOException {
    File fl = new File(filename);
    input = new FileInputStream(filename);
    numBitsRemaining = 0;
    isEndOfStream = false;
    totalbitstoread = (fl.length() * 8) - WriteBitsFile.pad;
  }

  // Returns the next bit (0 or 1), or -1 if end of stream
  public int readBit() throws IOException {
    if (totalbitstoread == 0) {
      return -1;
    }
    if (isEndOfStream) {
      return -1;
    }

    if (numBitsRemaining == 0) {
      currentByte = input.read();
      if (currentByte == -1) {
        isEndOfStream = true;
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
