package com.datacomp.ui;

import com.datacomp.config.AppConfig;
import com.datacomp.model.CompressionMetrics;
import com.datacomp.model.StageMetrics;
import com.datacomp.service.CompressionService;
import com.datacomp.service.MetricsService;
import com.datacomp.service.ServiceFactory;
import com.datacomp.service.cpu.CpuCompressionService;
import com.datacomp.service.gpu.GpuCompressionService;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Compression/decompression view controller with drag-and-drop support.
 */
public class CompressController implements MainViewController.ConfigurableController {
    
    private static final Logger logger = LoggerFactory.getLogger(CompressController.class);
    
    @FXML private VBox dragDropArea;
    @FXML private Label fileLabel;
    @FXML private TextField inputFileField;
    @FXML private TextField outputFileField;
    @FXML private Button compressButton;
    @FXML private Button decompressButton;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private Label statusLabel;
    @FXML private Label throughputLabel;
    @FXML private Label etaLabel;
    @FXML private CheckBox useCpuCheckBox;
    @FXML private VBox stageMetricsBox;
    @FXML private VBox stageMetricsContainer; // Changed from TextArea to VBox for cards
    
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
        
        // Setup input file field listener
        if (inputFileField != null) {
            inputFileField.textProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null && !newVal.trim().isEmpty()) {
                    selectedFile = Path.of(newVal);
                    updateButtonStates();
                    autoGenerateOutputPath();
                }
            });
        }
    }
    
    @Override
    public void setConfig(AppConfig config) {
        this.config = config;
        
        // Close old service if it exists
        if (this.compressionService instanceof AutoCloseable) {
            try {
                ((AutoCloseable) this.compressionService).close();
            } catch (Exception e) {
                logger.warn("Failed to close previous compression service: {}", e.getMessage());
            }
        }
        
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
        handleSelectInputFile();
    }
    
    @FXML
    private void handleSelectInputFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Input File");
        
        File file = fileChooser.showOpenDialog(inputFileField != null ? 
            inputFileField.getScene().getWindow() : 
            dragDropArea.getScene().getWindow());
        
        if (file != null) {
            selectedFile = file.toPath();
            if (inputFileField != null) {
                inputFileField.setText(file.getAbsolutePath());
            }
            if (fileLabel != null) {
                fileLabel.setText(file.getName());
            }
            updateButtonStates();
            autoGenerateOutputPath();
        }
    }
    
    @FXML
    private void handleSelectOutputFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Output File");
        
        // Set initial directory to input file's parent if available
        if (selectedFile != null && selectedFile.getParent() != null) {
            fileChooser.setInitialDirectory(selectedFile.getParent().toFile());
        }
        
        // Suggest default filename
        if (selectedFile != null && outputFileField != null) {
            String defaultName = selectedFile.getFileName().toString();
            if (isCompressedFile(selectedFile)) {
                // For decompression, remove extension
                if (defaultName.endsWith(config.getCompressedExtension())) {
                    defaultName = defaultName.substring(0, defaultName.length() - config.getCompressedExtension().length());
                }
            } else {
                // For compression, add extension
                defaultName = defaultName + config.getCompressedExtension();
            }
            fileChooser.setInitialFileName(defaultName);
        }
        
        File file = fileChooser.showSaveDialog(outputFileField.getScene().getWindow());
        
        if (file != null && outputFileField != null) {
            outputFileField.setText(file.getAbsolutePath());
        }
    }
    
    private void autoGenerateOutputPath() {
        if (selectedFile == null || outputFileField == null) return;
        
        String outputName;
        if (isCompressedFile(selectedFile)) {
            // For decompression
            String fileName = selectedFile.getFileName().toString();
            if (fileName.endsWith(config.getCompressedExtension())) {
                outputName = fileName.substring(0, fileName.length() - config.getCompressedExtension().length());
            } else {
                outputName = fileName + ".decompressed";
            }
        } else {
            // For compression
            outputName = selectedFile.getFileName().toString() + config.getCompressedExtension();
        }
        
        Path outputPath = selectedFile.getParent().resolve(outputName);
        outputFileField.setText(outputPath.toString());
    }
    
    private boolean isCompressedFile(Path file) {
        return file != null && file.toString().endsWith(config.getCompressedExtension());
    }
    
    @FXML
    private void handleCompress() {
        if (selectedFile == null) return;
        
        // Determine output path from field or auto-generate
        Path outputPath;
        if (outputFileField != null && !outputFileField.getText().trim().isEmpty()) {
            outputPath = Path.of(outputFileField.getText());
        } else {
            String outputName = selectedFile.getFileName().toString() + config.getCompressedExtension();
            outputPath = selectedFile.getParent().resolve(outputName);
        }
        
        // Update service based on checkbox
        if (useCpuCheckBox.isSelected() != config.isForceCpu()) {
            // Close old service before creating new one
            if (compressionService instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) compressionService).close();
                } catch (Exception e) {
                    logger.warn("Failed to close compression service: {}", e.getMessage());
                }
            }
            compressionService = ServiceFactory.createCompressionService(config);
        }
        
        // Determine processor type for metrics
        final String processorType = useCpuCheckBox != null && useCpuCheckBox.isSelected() ? "CPU" : "GPU";
        final Path finalOutputPath = outputPath;
        
        // Clear previous metrics display
        if (stageMetricsContainer != null) {
            stageMetricsContainer.getChildren().clear();
        }
        if (stageMetricsBox != null) {
            stageMetricsBox.setManaged(false);
            stageMetricsBox.setVisible(false);
        }
        
        // Run compression in background
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                long startTime = System.nanoTime();
                long fileSize = Files.size(selectedFile);
                
                updateMessage("Compressing...");
                
                compressionService.compress(selectedFile, finalOutputPath, progress -> {
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
                
                // Record metrics after successful compression
                long endTime = System.nanoTime();
                double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
                long inputSize = Files.size(selectedFile);
                long outputSize = Files.size(finalOutputPath);
                double avgThroughput = (inputSize / 1_000_000.0) / durationSeconds;
                
                CompressionMetrics metrics = new CompressionMetrics(
                    selectedFile.getFileName().toString(),
                    CompressionMetrics.OperationType.COMPRESS,
                    inputSize,
                    outputSize,
                    avgThroughput,
                    durationSeconds,
                    processorType
                );
                
                MetricsService.getInstance().addMetrics(metrics);
                
                // Get stage metrics from service
                StageMetrics stageMetrics = null;
                if (compressionService instanceof CpuCompressionService) {
                    stageMetrics = ((CpuCompressionService) compressionService).getLastStageMetrics();
                    logger.debug("Retrieved CPU compression metrics: {} stages", 
                        stageMetrics != null ? stageMetrics.getAllStageTimes().size() : 0);
                } else if (compressionService instanceof GpuCompressionService) {
                    stageMetrics = ((GpuCompressionService) compressionService).getLastStageMetrics();
                    logger.debug("Retrieved GPU compression metrics: {} stages", 
                        stageMetrics != null ? stageMetrics.getAllStageTimes().size() : 0);
                }
                
                // Display stage metrics
                final StageMetrics finalMetrics = stageMetrics;
                if (finalMetrics != null) {
                    Platform.runLater(() -> {
                        logger.info("Displaying compression metrics with {} stages", finalMetrics.getAllStageTimes().size());
                        displayStageMetrics(finalMetrics);
                    });
                } else {
                    logger.warn("No compression metrics available to display");
                }
                
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
        
        // Determine output path from field or auto-generate
        Path outputPath;
        if (outputFileField != null && !outputFileField.getText().trim().isEmpty()) {
            outputPath = Path.of(outputFileField.getText());
        } else {
            String fileName = selectedFile.getFileName().toString();
            if (fileName.endsWith(config.getCompressedExtension())) {
                fileName = fileName.substring(0, fileName.length() - config.getCompressedExtension().length());
            } else {
                fileName = fileName + ".decompressed";
            }
            outputPath = selectedFile.getParent().resolve(fileName);
        }
        
        // Determine processor type for metrics
        final String processorType = useCpuCheckBox != null && useCpuCheckBox.isSelected() ? "CPU" : "GPU";
        final Path finalOutputPath = outputPath;
        
        // Clear previous metrics display
        if (stageMetricsContainer != null) {
            stageMetricsContainer.getChildren().clear();
        }
        if (stageMetricsBox != null) {
            stageMetricsBox.setManaged(false);
            stageMetricsBox.setVisible(false);
        }
        
        // Run decompression in background
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                long startTime = System.nanoTime();
                long fileSize = Files.size(selectedFile);
                
                updateMessage("Decompressing...");
                
                compressionService.decompress(selectedFile, finalOutputPath, progress -> {
                    updateProgress(progress, 1.0);
                    
                    // Calculate throughput
                    long elapsed = System.nanoTime() - startTime;
                    double throughputMBps = (fileSize * progress / 1_000_000.0) / (elapsed / 1_000_000_000.0);
                    double remainingTime = (elapsed / progress - elapsed) / 1_000_000_000.0;
                    
                    Platform.runLater(() -> {
                        throughputLabel.setText(String.format("%.2f MB/s", throughputMBps));
                        etaLabel.setText(String.format("ETA: %.1fs", remainingTime));
                    });
                });
                
                // Record metrics after successful decompression
                long endTime = System.nanoTime();
                double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
                long inputSize = Files.size(selectedFile);
                long outputSize = Files.size(finalOutputPath);
                double avgThroughput = (inputSize / 1_000_000.0) / durationSeconds;
                
                CompressionMetrics metrics = new CompressionMetrics(
                    selectedFile.getFileName().toString(),
                    CompressionMetrics.OperationType.DECOMPRESS,
                    inputSize,
                    outputSize,
                    avgThroughput,
                    durationSeconds,
                    processorType
                );
                
                MetricsService.getInstance().addMetrics(metrics);
                
                // Get stage metrics from service
                StageMetrics stageMetrics = null;
                if (compressionService instanceof CpuCompressionService) {
                    stageMetrics = ((CpuCompressionService) compressionService).getLastStageMetrics();
                    logger.debug("Retrieved CPU decompression metrics: {} stages", 
                        stageMetrics != null ? stageMetrics.getAllStageTimes().size() : 0);
                } else if (compressionService instanceof GpuCompressionService) {
                    stageMetrics = ((GpuCompressionService) compressionService).getLastStageMetrics();
                    logger.debug("Retrieved GPU decompression metrics: {} stages", 
                        stageMetrics != null ? stageMetrics.getAllStageTimes().size() : 0);
                }
                
                // Display stage metrics
                final StageMetrics finalMetrics = stageMetrics;
                if (finalMetrics != null) {
                    Platform.runLater(() -> {
                        logger.info("Displaying decompression metrics with {} stages", finalMetrics.getAllStageTimes().size());
                        displayStageMetrics(finalMetrics);
                    });
                } else {
                    logger.warn("No decompression metrics available to display");
                }
                
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
        
        boolean isCompressed = isCompressedFile(selectedFile);
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
    
    /**
     * Display stage performance metrics in a modern card-based UI.
     */
    private void displayStageMetrics(StageMetrics metrics) {
        if (stageMetricsBox == null || stageMetricsContainer == null || metrics == null) {
            return;
        }
        
        stageMetricsContainer.getChildren().clear();
        
        // Get total time for percentage calculations
        long totalTimeNs = 0;
        for (StageMetrics.Stage stage : StageMetrics.Stage.values()) {
            if (metrics.getStageCount(stage) > 0) {
                totalTimeNs += (long)(metrics.getStageTimeMs(stage) * 1_000_000);
            }
        }
        
        if (totalTimeNs == 0) {
            return; // No metrics to display
        }
        
        // Create card for each stage
        for (StageMetrics.Stage stage : StageMetrics.Stage.values()) {
            if (metrics.getStageCount(stage) > 0) {
                VBox card = createStageCard(stage, metrics);
                stageMetricsContainer.getChildren().add(card);
            }
        }
        
        // Show the metrics box
        stageMetricsBox.setManaged(true);
        stageMetricsBox.setVisible(true);
    }
    
    /**
     * Create a modern card UI for a single stage metric.
     */
    private VBox createStageCard(StageMetrics.Stage stage, StageMetrics metrics) {
        VBox card = new VBox(5);
        card.setStyle(
            "-fx-background-color: #2b2b2b;" +
            "-fx-background-radius: 8;" +
            "-fx-padding: 12;" +
            "-fx-border-color: #3a3a3a;" +
            "-fx-border-radius: 8;" +
            "-fx-border-width: 1;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 5, 0, 0, 2);"
        );
        
        // Stage name header
        Label nameLabel = new Label(stage.getDisplayName());
        nameLabel.setStyle(
            "-fx-font-size: 14px;" +
            "-fx-font-weight: bold;" +
            "-fx-text-fill: #e0e0e0;"
        );
        
        // Metrics row container
        javafx.scene.layout.HBox metricsRow = new javafx.scene.layout.HBox(20);
        metricsRow.setStyle("-fx-padding: 5 0 0 0;");
        
        // Time metric
        VBox timeBox = createMetricBox("Time", 
            String.format("%.2f ms", metrics.getStageTimeMs(stage)), 
            "#4CAF50");
        
        // Percentage metric
        VBox percentBox = createMetricBox("% of Total", 
            String.format("%.1f%%", metrics.getStagePercentage(stage)), 
            "#2196F3");
        
        // Runs metric
        VBox runsBox = createMetricBox("Runs", 
            String.valueOf(metrics.getStageCount(stage)), 
            "#FF9800");
        
        // Average time metric
        VBox avgBox = createMetricBox("Avg Time", 
            String.format("%.2f ms", metrics.getAverageStageTimeMs(stage)), 
            "#9C27B0");
        
        metricsRow.getChildren().addAll(timeBox, percentBox, runsBox, avgBox);
        
        // Throughput metric (if available)
        double throughput = metrics.getStageThroughputMBps(stage);
        if (throughput > 0) {
            VBox throughputBox = createMetricBox("Throughput", 
                String.format("%.2f MB/s", throughput), 
                "#F44336");
            metricsRow.getChildren().add(throughputBox);
        }
        
        // Progress bar for percentage visualization
        javafx.scene.control.ProgressBar progressBar = new javafx.scene.control.ProgressBar(
            metrics.getStagePercentage(stage) / 100.0
        );
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setStyle(
            "-fx-accent: #4CAF50;" +
            "-fx-pref-height: 4;"
        );
        javafx.scene.layout.VBox.setMargin(progressBar, new javafx.geometry.Insets(5, 0, 0, 0));
        
        card.getChildren().addAll(nameLabel, metricsRow, progressBar);
        
        return card;
    }
    
    /**
     * Create a metric box with label and value.
     */
    private VBox createMetricBox(String label, String value, String accentColor) {
        VBox box = new VBox(2);
        
        Label labelText = new Label(label);
        labelText.setStyle(
            "-fx-font-size: 10px;" +
            "-fx-text-fill: #999999;"
        );
        
        Label valueText = new Label(value);
        valueText.setStyle(
            "-fx-font-size: 13px;" +
            "-fx-font-weight: bold;" +
            "-fx-text-fill: " + accentColor + ";"
        );
        
        box.getChildren().addAll(labelText, valueText);
        
        return box;
    }
    
    /**
     * Cleanup resources when controller is destroyed.
     * MUST be called to prevent memory leaks.
     */
    public void cleanup() {
        logger.info("Cleaning up CompressController resources");
        
        // Shutdown executor
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Close compression service
        if (compressionService instanceof AutoCloseable) {
            try {
                ((AutoCloseable) compressionService).close();
                logger.info("✅ Compression service closed successfully");
            } catch (Exception e) {
                logger.error("Failed to close compression service: {}", e.getMessage());
            }
        }
    }
}

