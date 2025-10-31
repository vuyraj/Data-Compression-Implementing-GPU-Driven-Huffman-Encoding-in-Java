package com.datacomp.ui;

import com.datacomp.config.AppConfig;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for the Settings view.
 */
public class SettingsController implements MainViewController.ConfigurableController {
    
    private static final Logger logger = LoggerFactory.getLogger(SettingsController.class);
    
    // In-memory settings storage (persists across view switches)
    private static final Map<String, Object> settingsStore = new HashMap<>();
    
    @FXML private CheckBox autoDetectGpuCheckBox;
    @FXML private CheckBox fallbackToCpuCheckBox;
    @FXML private ComboBox<String> preferredDeviceComboBox;
    
    @FXML private Spinner<Integer> chunkSizeSpinner;
    @FXML private CheckBox verifyAfterCompressionCheckBox;
    @FXML private CheckBox keepOriginalFileCheckBox;
    
    @FXML private ComboBox<String> themeComboBox;
    @FXML private CheckBox enableAnimationsCheckBox;
    
    private AppConfig config;
    
    @FXML
    public void initialize() {
        logger.debug("Initializing settings controller");
        
        // Initialize chunk size spinner
        if (chunkSizeSpinner != null) {
            SpinnerValueFactory<Integer> valueFactory = 
                new SpinnerValueFactory.IntegerSpinnerValueFactory(16, 128, 32, 16);
            chunkSizeSpinner.setValueFactory(valueFactory);
            chunkSizeSpinner.setEditable(true);
        }
    }
    
    @Override
    public void setConfig(AppConfig config) {
        this.config = config;
        loadSettings();
    }
    
    private void loadSettings() {
        if (config == null) return;
        
        // GPU Settings - load from store if available, otherwise from config
        if (autoDetectGpuCheckBox != null) {
            boolean autoDetect = (boolean) settingsStore.getOrDefault("gpu.autoDetect", config.isGpuAutoDetect());
            autoDetectGpuCheckBox.setSelected(autoDetect);
        }
        if (fallbackToCpuCheckBox != null) {
            boolean fallback = (boolean) settingsStore.getOrDefault("gpu.fallbackOnError", config.isGpuFallbackOnError());
            fallbackToCpuCheckBox.setSelected(fallback);
        }
        if (preferredDeviceComboBox != null) {
            String device = (String) settingsStore.getOrDefault("gpu.preferredDevice", config.getPreferredDevice());
            preferredDeviceComboBox.setValue(device);
        }
        
        // Compression Settings
        if (chunkSizeSpinner != null) {
            int chunkSize = (int) settingsStore.getOrDefault("compression.chunkSize", config.getChunkSizeMB());
            chunkSizeSpinner.getValueFactory().setValue(chunkSize);
        }
        if (verifyAfterCompressionCheckBox != null) {
            boolean verify = (boolean) settingsStore.getOrDefault("output.verifyAfterCompress", config.isVerifyAfterCompress());
            verifyAfterCompressionCheckBox.setSelected(verify);
        }
        if (keepOriginalFileCheckBox != null) {
            boolean keep = (boolean) settingsStore.getOrDefault("output.keepOriginal", config.isKeepOriginal());
            keepOriginalFileCheckBox.setSelected(keep);
        }
        
        // UI Settings
        if (themeComboBox != null) {
            String theme = (String) settingsStore.getOrDefault("ui.theme", config.getTheme());
            themeComboBox.setValue(theme);
        }
        if (enableAnimationsCheckBox != null) {
            boolean animations = (boolean) settingsStore.getOrDefault("ui.animationsEnabled", config.isAnimationsEnabled());
            enableAnimationsCheckBox.setSelected(animations);
        }
        
        logger.info("Settings loaded from configuration");
    }
    
    @FXML
    private void handleSaveSettings() {
        if (config == null) {
            showError("Configuration not available");
            return;
        }
        
        try {
            // Save GPU Settings to in-memory store
            if (autoDetectGpuCheckBox != null) {
                settingsStore.put("gpu.autoDetect", autoDetectGpuCheckBox.isSelected());
                logger.info("GPU auto-detect: {}", autoDetectGpuCheckBox.isSelected());
            }
            if (fallbackToCpuCheckBox != null) {
                settingsStore.put("gpu.fallbackOnError", fallbackToCpuCheckBox.isSelected());
                logger.info("GPU fallback on error: {}", fallbackToCpuCheckBox.isSelected());
            }
            if (preferredDeviceComboBox != null) {
                settingsStore.put("gpu.preferredDevice", preferredDeviceComboBox.getValue());
                logger.info("Preferred device: {}", preferredDeviceComboBox.getValue());
            }
            
            // Save Compression Settings
            if (chunkSizeSpinner != null) {
                int chunkSize = chunkSizeSpinner.getValue();
                settingsStore.put("compression.chunkSize", chunkSize);
                logger.info("Chunk size: {} MB", chunkSize);
            }
            if (verifyAfterCompressionCheckBox != null) {
                settingsStore.put("output.verifyAfterCompress", verifyAfterCompressionCheckBox.isSelected());
                logger.info("Verify after compression: {}", verifyAfterCompressionCheckBox.isSelected());
            }
            if (keepOriginalFileCheckBox != null) {
                settingsStore.put("output.keepOriginal", keepOriginalFileCheckBox.isSelected());
                logger.info("Keep original file: {}", keepOriginalFileCheckBox.isSelected());
            }
            
            // Save UI Settings
            if (themeComboBox != null) {
                settingsStore.put("ui.theme", themeComboBox.getValue());
                logger.info("Theme: {}", themeComboBox.getValue());
            }
            if (enableAnimationsCheckBox != null) {
                settingsStore.put("ui.animationsEnabled", enableAnimationsCheckBox.isSelected());
                logger.info("Enable animations: {}", enableAnimationsCheckBox.isSelected());
            }
            
            showInfo("Settings saved successfully!");
            logger.info("Settings saved successfully");
            
        } catch (Exception e) {
            logger.error("Failed to save settings", e);
            showError("Failed to save settings: " + e.getMessage());
        }
    }
    
    @FXML
    private void handleResetToDefaults() {
        try {
            // Reset GPU Settings
            if (autoDetectGpuCheckBox != null) {
                autoDetectGpuCheckBox.setSelected(true);
            }
            if (fallbackToCpuCheckBox != null) {
                fallbackToCpuCheckBox.setSelected(true);
            }
            if (preferredDeviceComboBox != null) {
                preferredDeviceComboBox.setValue("Any");
            }
            
            // Reset Compression Settings
            if (chunkSizeSpinner != null) {
                chunkSizeSpinner.getValueFactory().setValue(32);
            }
            if (verifyAfterCompressionCheckBox != null) {
                verifyAfterCompressionCheckBox.setSelected(true);
            }
            if (keepOriginalFileCheckBox != null) {
                keepOriginalFileCheckBox.setSelected(true);
            }
            
            // Reset UI Settings
            if (themeComboBox != null) {
                themeComboBox.setValue("Dark");
            }
            if (enableAnimationsCheckBox != null) {
                enableAnimationsCheckBox.setSelected(true);
            }
            
            showInfo("Settings reset to defaults");
            logger.info("Settings reset to defaults");
            
        } catch (Exception e) {
            logger.error("Failed to reset settings", e);
            showError("Failed to reset settings: " + e.getMessage());
        }
    }
    
    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Settings");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Settings Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
