package com.datacomp.ui;

import com.datacomp.config.AppConfig;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Main JavaFX application class for DataComp.
 */
public class DataCompApp extends Application {
    
    private static final Logger logger = LoggerFactory.getLogger(DataCompApp.class);
    
    private AppConfig config;
    private Stage primaryStage;
    private MainViewController mainController;
    
    @Override
    public void init() throws Exception {
        logger.info("Initializing DataComp application");
        config = new AppConfig();
        
        // Add shutdown hook for cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("🛑 Shutdown hook triggered - forcing cleanup");
            try {
                if (mainController != null) {
                    mainController.cleanup();
                }
                // Force aggressive garbage collection
                System.gc();
                System.gc();
                logger.info("✅ Shutdown cleanup complete");
            } catch (Exception e) {
                logger.error("Error during shutdown cleanup: {}", e.getMessage());
            }
        }, "Cleanup-Thread"));
    }
    
    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        
        try {
            // Load main view
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
            Parent root = loader.load();
            
            // Get controller and inject dependencies
            MainViewController controller = loader.getController();
            controller.setConfig(config);
            this.mainController = controller; // Keep reference for cleanup
            
            // Create scene with theme
            Scene scene = new Scene(root, config.getWindowWidth(), config.getWindowHeight());
            applyTheme(scene);
            
            // Setup stage
            primaryStage.setTitle("DataComp - GPU-Accelerated Compression");
            primaryStage.setScene(scene);
            primaryStage.setResizable(config.isWindowResizable());
            
            // Add icon if available
            // primaryStage.getIcons().add(new Image(getClass().getResourceAsStream("/icons/app-icon.png")));
            
            primaryStage.show();
            
            logger.info("Application started successfully");
            
        } catch (IOException e) {
            logger.error("Failed to load main view", e);
            showErrorAndExit("Failed to start application: " + e.getMessage());
        }
    }
    
    private void applyTheme(Scene scene) {
        String theme = config.getTheme();
        String cssFile = theme.equals("dark") ? "/css/dark-theme.css" : "/css/light-theme.css";
        
        try {
            String css = getClass().getResource(cssFile).toExternalForm();
            scene.getStylesheets().add(css);
            logger.debug("Applied theme: {}", theme);
        } catch (Exception e) {
            logger.warn("Failed to load theme CSS: {}", cssFile, e);
        }
    }
    
    private void showErrorAndExit(String message) {
        logger.error(message);
        System.err.println(message);
        System.exit(1);
    }
    
    @Override
    public void stop() throws Exception {
        logger.info("Application stopping - cleaning up resources");
        
        // Cleanup main controller and all child controllers
        if (mainController != null) {
            mainController.cleanup();
        }
        
        // Force aggressive garbage collection
        logger.info("🗑️ Forcing garbage collection...");
        System.gc();
        System.gc();
        
        super.stop();
        logger.info("✅ Application stopped successfully");
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}

