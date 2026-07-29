package de.keksuccino.rinku.listeners;

@FunctionalInterface
public interface RinkuCursorChangeListener {
    void onCursorChange(int cursorID);
}
