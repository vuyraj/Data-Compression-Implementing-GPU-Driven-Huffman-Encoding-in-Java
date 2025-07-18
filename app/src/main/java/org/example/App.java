package org.example;

import java.io.FileInputStream;

public class App {

  public static void main(String[] args) {

    System.out.println("Data Compression");

    cpuHuffman huff = new cpuHuffman();

    try {
      FileInputStream in = new FileInputStream("/home/vuyraj/git/Data-Compression-Implementing-GPU-Driven-Huffman-Encoding-in-Java/app/input");
      int inp;

      while ((inp = in.read()) != -1) {

        huff.frequencyCount(inp);

      }

      Node rootNode = huff.huffmanTree();

      huff.codeBookCreation(rootNode, "");
      huff.printCodebook();
      System.out.println("Now compressing the file...");
      huff.compress("/home/vuyraj/git/Data-Compression-Implementing-GPU-Driven-Huffman-Encoding-in-Java/app/input", "/home/vuyraj/git/Data-Compression-Implementing-GPU-Driven-Huffman-Encoding-in-Java/app/compressed.sz");
      in.close();
    } catch (Exception e) {
      System.out.println("error occured: " + e);
    }

  }

}
