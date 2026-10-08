module com.example.app {
    requires transitive javafx.graphics;
    requires javafx.controls;
    requires java.sql;
    requires java.logging;
    requires com.h2database;

    exports com.example.app;
}
