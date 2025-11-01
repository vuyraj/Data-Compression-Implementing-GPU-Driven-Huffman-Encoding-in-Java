package com.datacomp.ui;

import com.datacomp.config.AppConfig;
import com.datacomp.model.CompressionMetrics;
import com.datacomp.service.MetricsService;
import com.datacomp.service.gpu.GpuFrequencyService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.common.TornadoDevice;

import java.util.List;

/**
 * Dashboard view controller showing overview and recent activity.
 */
public class DashboardController implements MainViewController.ConfigurableController {
    
    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);
    
    @FXML private Label gpuNameLabel;
    @FXML private Label gpuStatusLabel;
    @FXML private Label cpuCoresLabel;
    @FXML private ListView<String> recentFilesListView;
    @FXML private LineChart<String, Number> performanceChart;
    
    // Metrics display
    @FXML private Label lastOperationLabel;
    @FXML private Label lastThroughputLabel;
    @FXML private Label lastCompressionRatioLabel;
    @FXML private Label lastDurationLabel;
    @FXML private Label avgThroughputLabel;
    @FXML private TableView<MetricsTableRow> metricsTable;
    @FXML private TableColumn<MetricsTableRow, String> timeColumn;
    @FXML private TableColumn<MetricsTableRow, String> operationColumn;
    @FXML private TableColumn<MetricsTableRow, String> fileColumn;
    @FXML private TableColumn<MetricsTableRow, String> sizeColumn;
    @FXML private TableColumn<MetricsTableRow, String> throughputColumn;
    @FXML private TableColumn<MetricsTableRow, String> processorColumn;
    
    private AppConfig config;
    private MetricsService metricsService;
    
    @FXML
    public void initialize() {
        logger.debug("Initializing dashboard controller");
        
        // Get metrics service instance
        metricsService = MetricsService.getInstance();
        
        // Setup performance chart
        if (performanceChart != null) {
            setupPerformanceChart();
        }
        
        // Setup metrics table
        if (metricsTable != null) {
            setupMetricsTable();
        }
        
        // Listen for metrics updates
        metricsService.addListener(this::updateMetricsDisplay);
    }
    
    @Override
    public void setConfig(AppConfig config) {
        this.config = config;
        Platform.runLater(() -> {
            updateSystemInfo();
            updateMetricsDisplay();
        });
    }
    
    private void setupMetricsTable() {
        // Setup table columns
        if (timeColumn != null) {
            timeColumn.setCellValueFactory(new PropertyValueFactory<>("time"));
        }
        if (operationColumn != null) {
            operationColumn.setCellValueFactory(new PropertyValueFactory<>("operation"));
        }
        if (fileColumn != null) {
            fileColumn.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        }
        if (sizeColumn != null) {
            sizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
        }
        if (throughputColumn != null) {
            throughputColumn.setCellValueFactory(new PropertyValueFactory<>("throughput"));
        }
        if (processorColumn != null) {
            processorColumn.setCellValueFactory(new PropertyValueFactory<>("processor"));
        }
    }
    
    private void updateMetricsDisplay() {
        Platform.runLater(() -> {
            // Update latest operation summary
            CompressionMetrics latest = metricsService.getLatestMetric();
            if (latest != null) {
                if (lastOperationLabel != null) {
                    String opType = latest.getOperationType() == CompressionMetrics.OperationType.COMPRESS 
                        ? "Compression" : "Decompression";
                    lastOperationLabel.setText(opType + ": " + latest.getFileName());
                }
                if (lastThroughputLabel != null) {
                    lastThroughputLabel.setText(String.format("%.2f MB/s", latest.getThroughputMBps()));
                }
                if (lastCompressionRatioLabel != null) {
                    if (latest.getOperationType() == CompressionMetrics.OperationType.COMPRESS) {
                        lastCompressionRatioLabel.setText(String.format("%.1f%% saved", latest.getSpaceSavedPercent()));
                    } else {
                        lastCompressionRatioLabel.setText("N/A");
                    }
                }
                if (lastDurationLabel != null) {
                    lastDurationLabel.setText(String.format("%.2fs", latest.getDurationSeconds()));
                }
            } else {
                if (lastOperationLabel != null) lastOperationLabel.setText("No operations yet");
                if (lastThroughputLabel != null) lastThroughputLabel.setText("-");
                if (lastCompressionRatioLabel != null) lastCompressionRatioLabel.setText("-");
                if (lastDurationLabel != null) lastDurationLabel.setText("-");
            }
            
            // Update average throughput
            if (avgThroughputLabel != null) {
                double avgThroughput = metricsService.getAverageThroughput();
                if (avgThroughput > 0) {
                    avgThroughputLabel.setText(String.format("%.2f MB/s", avgThroughput));
                } else {
                    avgThroughputLabel.setText("-");
                }
            }
            
            // Update metrics table
            if (metricsTable != null) {
                metricsTable.getItems().clear();
                List<CompressionMetrics> recentMetrics = metricsService.getRecentMetrics(10);
                for (CompressionMetrics m : recentMetrics) {
                    metricsTable.getItems().add(new MetricsTableRow(m));
                }
            }
            
            // Update performance chart
            updatePerformanceChart();
        });
    }
    
    private void updatePerformanceChart() {
        if (performanceChart == null) return;
        
        List<CompressionMetrics> metrics = metricsService.getRecentMetrics(10);
        if (metrics.isEmpty()) return;
        
        performanceChart.getData().clear();
        
        XYChart.Series<String, Number> compressSeries = new XYChart.Series<>();
        compressSeries.setName("Compression");
        
        XYChart.Series<String, Number> decompressSeries = new XYChart.Series<>();
        decompressSeries.setName("Decompression");
        
        // Reverse to show oldest to newest
        for (int i = metrics.size() - 1; i >= 0; i--) {
            CompressionMetrics m = metrics.get(i);
            String label = m.getTimestamp().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            
            if (m.getOperationType() == CompressionMetrics.OperationType.COMPRESS) {
                compressSeries.getData().add(new XYChart.Data<>(label, m.getThroughputMBps()));
            } else {
                decompressSeries.getData().add(new XYChart.Data<>(label, m.getThroughputMBps()));
            }
        }
        
        if (!compressSeries.getData().isEmpty()) {
            performanceChart.getData().add(compressSeries);
        }
        if (!decompressSeries.getData().isEmpty()) {
            performanceChart.getData().add(decompressSeries);
        }
    }
    
    private void updateSystemInfo() {
        // CPU info
        int cores = Runtime.getRuntime().availableProcessors();
        if (cpuCoresLabel != null) {
            cpuCoresLabel.setText(cores + " cores");
        }
        
        // GPU info
        try {
            List<TornadoDevice> devices = GpuFrequencyService.getAvailableDevices();
            
            if (!devices.isEmpty()) {
                TornadoDevice device = devices.get(0);
                if (gpuNameLabel != null) {
                    gpuNameLabel.setText(device.getDeviceName());
                }
                if (gpuStatusLabel != null) {
                    gpuStatusLabel.setText("Available");
                    gpuStatusLabel.setStyle("-fx-text-fill: #51cf66;");
                }
            } else {
                if (gpuNameLabel != null) {
                    gpuNameLabel.setText("No GPU detected");
                }
                if (gpuStatusLabel != null) {
                    gpuStatusLabel.setText("Unavailable");
                    gpuStatusLabel.setStyle("-fx-text-fill: #ff6b6b;");
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to query GPU info", e);
            if (gpuNameLabel != null) {
                gpuNameLabel.setText("Error querying GPU");
            }
        }
        
        // Recent files (placeholder)
        if (recentFilesListView != null) {
            recentFilesListView.getItems().clear();
            recentFilesListView.getItems().add("No recent files");
        }
    }
    
    private void setupPerformanceChart() {
        performanceChart.setTitle("Recent Performance");
        performanceChart.setAnimated(config != null && config.isAnimationsEnabled());
        
        XYChart.Series<String, Number> cpuSeries = new XYChart.Series<>();
        cpuSeries.setName("CPU");
        
        XYChart.Series<String, Number> gpuSeries = new XYChart.Series<>();
        gpuSeries.setName("GPU");
        
        performanceChart.getData().add(cpuSeries);
        performanceChart.getData().add(gpuSeries);
    }
    
    /**
     * Table row model for metrics display.
     */
    public static class MetricsTableRow {
        private final String time;
        private final String operation;
        private final String fileName;
        private final String size;
        private final String throughput;
        private final String processor;
        
        public MetricsTableRow(CompressionMetrics metrics) {
            this.time = metrics.getTimestamp().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            this.operation = metrics.getOperationType() == CompressionMetrics.OperationType.COMPRESS 
                ? "Compress" : "Decompress";
            this.fileName = metrics.getFileName();
            this.size = CompressionMetrics.formatSize(metrics.getInputSizeBytes()) + 
                " → " + CompressionMetrics.formatSize(metrics.getOutputSizeBytes());
            this.throughput = String.format("%.2f MB/s", metrics.getThroughputMBps());
            this.processor = metrics.getProcessorType();
        }
        
        public String getTime() { return time; }
        public String getOperation() { return operation; }
        public String getFileName() { return fileName; }
        public String getSize() { return size; }
        public String getThroughput() { return throughput; }
        public String getProcessor() { return processor; }
    }
}

