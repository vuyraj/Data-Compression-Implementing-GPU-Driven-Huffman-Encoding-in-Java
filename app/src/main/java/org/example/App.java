package org.example;

import java.io.FileInputStream;

public class App {

  public static void main(String[] args) {

    System.out.println("Data Compression");

    cpuHuffman huff = new cpuHuffman();

    try {
      FileInputStream in = new FileInputStream("input");
      int inp;

      while ((inp = in.read()) != -1) {

        huff.frequencyCount(inp);

      }

      Node rootNode = huff.huffmanTree();

      huff.codeBookCreation(rootNode, "");
      huff.printCodebook();
      in.close();
    } catch (Exception e) {
      System.out.println("eror occures" + e);
    }

  }

}
