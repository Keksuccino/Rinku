package de.keksuccino.mcef.listeners;

@FunctionalInterface
public interface MCEFInitListener {
    /**
     * @param successful whether MCEF was successfully initialized
     *                   If this is true, that means the user's platform is supported, natives downloaded registered properly, etc
     */
    void onInit(boolean successful);
}
