package app.almazium.samsunglens;

import java.util.Locale;

/** Texts shown inside the camera process, where module resources are not available. */
final class Ui {
    private Ui() {}

    static String text(String ru, String en) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? ru : en;
    }
}
