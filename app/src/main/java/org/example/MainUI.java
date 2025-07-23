package org.example;

import javafx.application.Application;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.FileChooser;
import java.io.File;
import java.io.FileInputStream;

public class MainUI extends Application {

    private Label statusLabel;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Huffman Compression");

        VBox layout = new VBox(10);
        layout.setStyle("-fx-padding: 10;");

        Button compressButton = new Button("Compress File");
        Button decompressButton = new Button("Decompress File");
        statusLabel = new Label("Status: Ready");

        compressButton.setOnAction(e -> compressFile(primaryStage));
        decompressButton.setOnAction(e -> decompressFile(primaryStage));

        layout.getChildren().addAll(compressButton, decompressButton, statusLabel);

        Scene scene = new Scene(layout, 300, 200);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void compressFile(Stage stage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Compress");
        File inputFile = fileChooser.showOpenDialog(stage);

        if (inputFile != null) {
            try {
                String compressedFile = inputFile.getParent() + "/compressed.sz";
                cpuHuffman huff = new cpuHuffman();
                FileInputStream in = new FileInputStream(inputFile);
                int inp;

                while ((inp = in.read()) != -1) {
                    huff.frequencyCount(inp);
                }

                Node rootNode = huff.huffmanTree();
                huff.codeBookCreation(rootNode, "");
                cpuCompression.compress(inputFile.getAbsolutePath(), compressedFile);
                in.close();

                statusLabel.setText("Compression Successful: " + compressedFile);
            } catch (Exception e) {
                statusLabel.setText("Error during compression: " + e.getMessage());
            }
        }
    }

    private void decompressFile(Stage stage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Decompress");
        File compressedFile = fileChooser.showOpenDialog(stage);

        if (compressedFile != null) {
            try {
                String decompressedFile = compressedFile.getParent() + "/output.txt";
                cpuDecompression dcmp = new cpuDecompression(compressedFile.getAbsolutePath(), decompressedFile);
                dcmp.decompress();
                dcmp.close();

                statusLabel.setText("Decompression Successful: " + decompressedFile);
            } catch (Exception e) {
                statusLabel.setText("Error during decompression: " + e.getMessage());
            }
        }
    }
    public static void main(String[] args) {
        launch(args);
    }
}
