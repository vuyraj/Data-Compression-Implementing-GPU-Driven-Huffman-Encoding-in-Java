package com.datacomp.ui;

import com.datacomp.config.AppConfig;
import com.datacomp.service.CompressionService;
import com.datacomp.service.ServiceFactory;
import com.datacomp.service.gpu.GpuFrequencyService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.common.TornadoDevice;

import java.io.IOException;
import java.util.List;

/**
 * Main view controller with navigation and content management.
 */
public class MainViewController {
    
    private static final Logger logger = LoggerFactory.getLogger(MainViewController.class);
    
    @FXML private BorderPane rootPane;
    @FXML private StackPane contentPane;
    @FXML private Label statusLabel;
    @FXML private Label gpuStatusLabel;
    @FXML private ToggleButton dashboardButton;
    @FXML private ToggleButton compressButton;
    @FXML private ToggleButton benchmarkButton;
    @FXML private ToggleButton settingsButton;
    
    private AppConfig config;
    private CompressionService compressionService;
    private ToggleGroup navigationGroup;
    private Object currentController; // Track current view controller for cleanup
    
    @FXML
    public void initialize() {
        logger.info("Initializing main view controller");
        
        // Setup navigation
        navigationGroup = new ToggleGroup();
        dashboardButton.setToggleGroup(navigationGroup);
        compressButton.setToggleGroup(navigationGroup);
        benchmarkButton.setToggleGroup(navigationGroup);
        settingsButton.setToggleGroup(navigationGroup);
        
        // Set default selection
        dashboardButton.setSelected(true);
    }
    
    public void setConfig(AppConfig config) {
        this.config = config;
        this.compressionService = ServiceFactory.createCompressionService(config);
        
        // Initialize views
        Platform.runLater(() -> {
            updateGpuStatus();
            showDashboard();
        });
    }
    
    @FXML
    private void showDashboard() {
        loadView("/fxml/DashboardView.fxml", "Dashboard");
    }
    
    @FXML
    private void showCompress() {
        loadView("/fxml/CompressView.fxml", "Compress");
    }
    
    @FXML
    private void showBenchmark() {
        loadView("/fxml/BenchmarkView.fxml", "Benchmark");
    }
    
    @FXML
    private void showSettings() {
        loadView("/fxml/SettingsView.fxml", "Settings");
    }
    
    private void loadView(String fxmlPath, String viewName) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent view = loader.load();
            
            // Inject config into controller if it has setConfig method
            Object controller = loader.getController();
            this.currentController = controller; // Track for cleanup
            
            if (controller instanceof ConfigurableController) {
                ((ConfigurableController) controller).setConfig(config);
            }
            
            contentPane.getChildren().clear();
            contentPane.getChildren().add(view);
            
            setStatus("Viewing: " + viewName);
            
        } catch (IOException e) {
            logger.error("Failed to load view: {}", fxmlPath, e);
            showError("Failed to load view: " + viewName);
        }
    }
    
    private void updateGpuStatus() {
        try {
            List<TornadoDevice> devices = GpuFrequencyService.getAvailableDevices();
            
            if (devices.isEmpty()) {
                gpuStatusLabel.setText("GPU: Not Available");
                gpuStatusLabel.setStyle("-fx-text-fill: #ff6b6b;");
            } else {
                TornadoDevice device = devices.get(0);
                gpuStatusLabel.setText("GPU: " + device.getDeviceName());
                gpuStatusLabel.setStyle("-fx-text-fill: #51cf66;");
            }
        } catch (Exception e) {
            gpuStatusLabel.setText("GPU: Error");
            gpuStatusLabel.setStyle("-fx-text-fill: #ffa500;");
            logger.warn("Failed to query GPU status", e);
        }
    }
    
    private void setStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }
    
    private void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    /**
     * Interface for controllers that need configuration.
     */
    public interface ConfigurableController {
        void setConfig(AppConfig config);
    }
    
    /**
     * Cleanup all resources when app is closing.
     */
    public void cleanup() {
        logger.info("Cleaning up MainViewController resources");
        
        // Cleanup current view controller if it has cleanup method
        if (currentController != null && currentController instanceof CompressController) {
            ((CompressController) currentController).cleanup();
        }
        
        // Close compression service
        if (compressionService instanceof AutoCloseable) {
            try {
                ((AutoCloseable) compressionService).close();
                logger.info("✅ Main controller compression service closed");
            } catch (Exception e) {
                logger.error("Failed to close compression service: {}", e.getMessage());
            }
        }
    }
}

