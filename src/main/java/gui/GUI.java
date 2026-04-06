package gui;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;

public class GUI extends Application{

    Button startButton;

    public static void main(String[] args) {
        launch(args);
    }
    
    public void start(Stage primaryStage) throws Exception{
        primaryStage.setTitle("Chrono Arena");
        startButton = new Button();
        startButton.setText("Start Game");

        BorderPane layout = new BorderPane();
        layout.setCenter(startButton);
        Scene screen = new Scene(layout, 1200, 800);
        primaryStage.setScene(screen);
        Font.loadFont("file:/Users/aditibaghel/Downloads/340ChronoArena/fonts/Fleftex_M.ttf", 20);
        screen.getStylesheets().add("file:/Users/aditibaghel/Downloads/340ChronoArena/stylesheet.css");
        startButton.getStyleClass().add("startButton");
        primaryStage.show();
    }
}
