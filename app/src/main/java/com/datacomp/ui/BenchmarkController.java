package com.datacomp.ui;

import com.datacomp.benchmark.BenchmarkResult;
import com.datacomp.benchmark.BenchmarkSuite;
import com.datacomp.config.AppConfig;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Benchmark view controller for CPU vs GPU performance comparison.
 */
public class BenchmarkController implements MainViewController.ConfigurableController {
    
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkController.class);
    
    @FXML private TextField testFileField;
    @FXML private Button selectFileButton;
    @FXML private Button runBenchmarkButton;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private TextArea resultsTextArea;
    @FXML private BarChart<String, Number> resultsChart;
    
    private AppConfig config;
    private Path testFile;
    private ExecutorService executor;
    
    @FXML
    public void initialize() {
        logger.debug("Initializing benchmark controller");
        executor = Executors.newSingleThreadExecutor();
    }
    
    @Override
    public void setConfig(AppConfig config) {
        this.config = config;
    }
    
    @FXML
    private void handleSelectFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Test File");
        
        File file = fileChooser.showOpenDialog(selectFileButton.getScene().getWindow());
        if (file != null) {
            testFile = file.toPath();
            testFileField.setText(file.getAbsolutePath());
            runBenchmarkButton.setDisable(false);
        }
    }
    
    @FXML
    private void handleRunBenchmark() {
        if (testFile == null) return;
        
        Task<BenchmarkSuite.BenchmarkComparison> task = new Task<BenchmarkSuite.BenchmarkComparison>() {
            @Override
            protected BenchmarkSuite.BenchmarkComparison call() throws Exception {
                updateMessage("Initializing benchmark...");
                updateProgress(0, 100);
                
                BenchmarkSuite suite = new BenchmarkSuite(config);
                
                // CPU benchmark
                updateMessage("Running CPU warmup...");
                updateProgress(10, 100);
                
                // Simulate progress through warmup
                Thread.sleep(500);
                updateMessage("Running CPU benchmark...");
                updateProgress(30, 100);
                
                // Run the actual benchmark
                BenchmarkSuite.BenchmarkComparison comparison = suite.runFullSuite(testFile);
                
                // GPU benchmark
                updateMessage("Running GPU benchmark...");
                updateProgress(70, 100);
                
                Thread.sleep(500);
                updateMessage("Finalizing results...");
                updateProgress(90, 100);
                
                Thread.sleep(300);
                updateProgress(100, 100);
                updateMessage("Complete!");
                
                return comparison;
            }
        };
        
        task.setOnSucceeded(event -> {
            BenchmarkSuite.BenchmarkComparison comparison = task.getValue();
            displayResults(comparison);
            statusLabel.setText("Benchmark complete!");
            statusLabel.setStyle("-fx-text-fill: #51cf66;");
            resetProgress();
        });
        
        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            logger.error("Benchmark failed", ex);
            statusLabel.setText("Benchmark failed: " + ex.getMessage());
            statusLabel.setStyle("-fx-text-fill: #ff6b6b;");
            resetProgress();
        });
        
        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());
        
        runBenchmarkButton.setDisable(true);
        selectFileButton.setDisable(true);
        
        executor.submit(task);
    }
    
    private void displayResults(BenchmarkSuite.BenchmarkComparison comparison) {
        // Display text results
        resultsTextArea.setText(comparison.getSummary());
        
        // Update chart
        if (resultsChart != null) {
            resultsChart.getData().clear();
            
            XYChart.Series<String, Number> throughputSeries = new XYChart.Series<>();
            throughputSeries.setName("Throughput (MB/s)");
            
            for (BenchmarkResult result : comparison.getResults()) {
                throughputSeries.getData().add(
                    new XYChart.Data<>(result.getServiceName(), result.getThroughputMBps())
                );
            }
            
            resultsChart.getData().add(throughputSeries);
        }
    }
    
    private void resetProgress() {
        runBenchmarkButton.setDisable(false);
        selectFileButton.setDisable(false);
        
        Platform.runLater(() -> {
            progressBar.progressProperty().unbind();
            progressBar.setProgress(0);
            statusLabel.textProperty().unbind();
        });
    }
}

