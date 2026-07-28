package de.keksuccino.rinku.listeners;

@FunctionalInterface
public interface RinkuInitListener {
    /**
     * @param successful whether Rinku was successfully initialized
     *                   If this is true, that means the user's platform is supported, natives downloaded registered properly, etc
     */
    void onInit(boolean successful);
}
