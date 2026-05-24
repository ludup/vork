package sh.vork.ui;

public interface SettingsPage {
    /**
     * Font Awesome icon class, e.g. "fa-user-cog" or "fa-key".
     */
    String getIcon();

    /**
     * Display name for the settings page.
     */
    String getName();

    /**
     * Short description for the settings page.
     */
    String getDescription();

    /**
     * Path segment for this settings page (e.g. "change-password").
     */
    String getPath();
}
