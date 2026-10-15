package com.example.app.ui;

import com.example.app.error.PosException;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A dialog that stays open, with the user's input, when the action fails. The action runs when OK is pressed. If it
 * throws a business error, the message appears in the dialog and nothing the user typed is lost.
 */
final class FormDialog {

    private static final Logger LOG = Logger.getLogger(FormDialog.class.getName());

    private FormDialog() {
    }

    /**
     * @return true if the action succeeded and the dialog was closed with OK; false if the user cancelled.
     */
    static boolean run(Window owner, String title, String header, Node content, String okText, Runnable onOk) {
        return showAndWait(build(owner, title, header, content, okText, onOk));
    }

    /** Shows the dialog and returns true if it was closed with its OK button, which is always the first button. */
    static boolean showAndWait(Dialog<ButtonType> dialog) {
        ButtonType ok = dialog.getDialogPane().getButtonTypes().get(0);
        return dialog.showAndWait().filter(choice -> choice == ok).isPresent();
    }

    /** The dialog, wired up but not shown. Tests use it to check the layout without blocking. */
    static Dialog<ButtonType> build(Window owner, String title, String header, Node content, String okText,
                                    Runnable onOk) {
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        Label problem = Ui.error("");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setResizable(true);
        dialog.getDialogPane().setMinWidth(440);
        double contentWidth = content instanceof Region region ? region.prefWidth(-1) : 0;
        dialog.getDialogPane().setPrefWidth(Math.max(620, contentWidth + 64));
        dialog.setHeaderText(header);
        dialog.getDialogPane().setContent(new VBox(10, content, problem));
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        // Each button keeps its own width. Uniform sizing would squeeze a longer label such as "Take payment" to "Take paym...".
        for (ButtonType type : dialog.getDialogPane().getButtonTypes()) {
            Region button = (Region) dialog.getDialogPane().lookupButton(type);
            ButtonBar.setButtonUniformSize(button, false);
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        ThemeManager.apply(dialog.getDialogPane());
        if (owner != null) {
            dialog.initOwner(owner);
        }

        Button okButton = (Button) dialog.getDialogPane().lookupButton(ok);
        boolean[] submitting = {false};
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (submitting[0]) {
                event.consume();
                return;
            }
            submitting[0] = true;
            okButton.setDisable(true);
            try {
                onOk.run();
                problem.setText("");
            } catch (PosException e) {
                problem.setText(e.getMessage());
                event.consume();
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Dialog action failed", e);
                problem.setText("Something went wrong. The details were written to the log.");
                event.consume();
            } finally {
                submitting[0] = false;
                okButton.setDisable(false);
            }
        });

        return dialog;
    }
}
