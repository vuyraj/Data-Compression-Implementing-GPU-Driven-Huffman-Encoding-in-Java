package com.datacomp.ui;

import com.datacomp.config.AppConfig;
import com.datacomp.service.gpu.GpuFrequencyService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
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
    
    private AppConfig config;
    
    @FXML
    public void initialize() {
        logger.debug("Initializing dashboard controller");
        
        // Setup performance chart
        if (performanceChart != null) {
            setupPerformanceChart();
        }
    }
    
    @Override
    public void setConfig(AppConfig config) {
        this.config = config;
        Platform.runLater(this::updateSystemInfo);
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
}

