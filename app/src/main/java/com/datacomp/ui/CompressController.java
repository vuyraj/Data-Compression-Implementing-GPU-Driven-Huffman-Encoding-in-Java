package com.datacomp.ui;

import com.datacomp.config.AppConfig;
import com.datacomp.service.CompressionService;
import com.datacomp.service.ServiceFactory;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Compression/decompression view controller with drag-and-drop support.
 */
public class CompressController implements MainViewController.ConfigurableController {
    
    private static final Logger logger = LoggerFactory.getLogger(CompressController.class);
    
    @FXML private VBox dragDropArea;
    @FXML private Label fileLabel;
    @FXML private Button compressButton;
    @FXML private Button decompressButton;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private Label statusLabel;
    @FXML private Label throughputLabel;
    @FXML private Label etaLabel;
    @FXML private CheckBox useCpuCheckBox;
    
    private AppConfig config;
    private CompressionService compressionService;
    private Path selectedFile;
    private ExecutorService executor;
    
    @FXML
    public void initialize() {
        logger.debug("Initializing compress controller");
        executor = Executors.newSingleThreadExecutor();
        
        // Setup drag and drop
        if (dragDropArea != null) {
            setupDragAndDrop();
        }
    }
    
    @Override
    public void setConfig(AppConfig config) {
        this.config = config;
        this.compressionService = ServiceFactory.createCompressionService(config);
    }
    
    private void setupDragAndDrop() {
        dragDropArea.setOnDragOver(this::handleDragOver);
        dragDropArea.setOnDragDropped(this::handleDragDropped);
    }
    
    private void handleDragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }
    
    private void handleDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        boolean success = false;
        
        if (db.hasFiles() && !db.getFiles().isEmpty()) {
            File file = db.getFiles().get(0);
            selectedFile = file.toPath();
            fileLabel.setText(file.getName());
            updateButtonStates();
            success = true;
        }
        
        event.setDropCompleted(success);
        event.consume();
    }
    
    @FXML
    private void handleSelectFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File");
        
        File file = fileChooser.showOpenDialog(dragDropArea.getScene().getWindow());
        if (file != null) {
            selectedFile = file.toPath();
            fileLabel.setText(file.getName());
            updateButtonStates();
        }
    }
    
    @FXML
    private void handleCompress() {
        if (selectedFile == null) return;
        
        // Determine output path
        String outputName = selectedFile.getFileName().toString() + config.getCompressedExtension();
        Path outputPath = selectedFile.getParent().resolve(outputName);
        
        // Update service based on checkbox
        if (useCpuCheckBox.isSelected() != config.isForceCpu()) {
            compressionService = ServiceFactory.createCompressionService(config);
        }
        
        // Run compression in background
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                long startTime = System.nanoTime();
                long fileSize = Files.size(selectedFile);
                
                updateMessage("Compressing...");
                
                compressionService.compress(selectedFile, outputPath, progress -> {
                    updateProgress(progress, 1.0);
                    
                    // Calculate throughput and ETA
                    long elapsed = System.nanoTime() - startTime;
                    double throughputMBps = (fileSize * progress / 1_000_000.0) / (elapsed / 1_000_000_000.0);
                    double remainingTime = (elapsed / progress - elapsed) / 1_000_000_000.0;
                    
                    Platform.runLater(() -> {
                        throughputLabel.setText(String.format("%.2f MB/s", throughputMBps));
                        etaLabel.setText(String.format("ETA: %.1fs", remainingTime));
                    });
                });
                
                return null;
            }
        };
        
        task.setOnSucceeded(event -> {
            statusLabel.setText("Compression complete!");
            statusLabel.setStyle("-fx-text-fill: #51cf66;");
            resetProgress();
        });
        
        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            logger.error("Compression failed", ex);
            statusLabel.setText("Compression failed: " + ex.getMessage());
            statusLabel.setStyle("-fx-text-fill: #ff6b6b;");
            resetProgress();
        });
        
        // Bind progress
        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());
        
        // Disable buttons during operation
        compressButton.setDisable(true);
        decompressButton.setDisable(true);
        
        executor.submit(task);
    }
    
    @FXML
    private void handleDecompress() {
        if (selectedFile == null) return;
        
        // Determine output path
        String fileName = selectedFile.getFileName().toString();
        if (fileName.endsWith(config.getCompressedExtension())) {
            fileName = fileName.substring(0, fileName.length() - config.getCompressedExtension().length());
        } else {
            fileName = fileName + ".decompressed";
        }
        
        Path outputPath = selectedFile.getParent().resolve(fileName);
        
        // Run decompression in background
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                updateMessage("Decompressing...");
                
                compressionService.decompress(selectedFile, outputPath, progress -> {
                    updateProgress(progress, 1.0);
                });
                
                return null;
            }
        };
        
        task.setOnSucceeded(event -> {
            statusLabel.setText("Decompression complete!");
            statusLabel.setStyle("-fx-text-fill: #51cf66;");
            resetProgress();
        });
        
        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            logger.error("Decompression failed", ex);
            statusLabel.setText("Decompression failed: " + ex.getMessage());
            statusLabel.setStyle("-fx-text-fill: #ff6b6b;");
            resetProgress();
        });
        
        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());
        
        compressButton.setDisable(true);
        decompressButton.setDisable(true);
        
        executor.submit(task);
    }
    
    private void updateButtonStates() {
        boolean hasFile = selectedFile != null && Files.exists(selectedFile);
        compressButton.setDisable(!hasFile);
        
        boolean isCompressed = hasFile && selectedFile.toString().endsWith(config.getCompressedExtension());
        decompressButton.setDisable(!isCompressed);
    }
    
    private void resetProgress() {
        compressButton.setDisable(false);
        decompressButton.setDisable(false);
        updateButtonStates();
        
        Platform.runLater(() -> {
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();
            progressBar.setProgress(0);
            progressLabel.setText("");
            throughputLabel.setText("");
            etaLabel.setText("");
        });
    }
}

