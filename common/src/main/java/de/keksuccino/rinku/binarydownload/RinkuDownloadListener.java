package de.keksuccino.rinku.binarydownload;

import net.minecraft.network.chat.Component;
import java.util.Objects;

public class RinkuDownloadListener {

    public static final RinkuDownloadListener INSTANCE = new RinkuDownloadListener();

    // The installer runs on Rinku-Downloader while the render thread polls this state. Volatile
    // publication keeps progress and the terminal state visible without tying either thread to a
    // UI lock during network and extraction work.
    private volatile Component task = Component.empty();
    private volatile float percent;
    private volatile boolean done;
    private volatile boolean failed;

    private RinkuDownloadListener() {}

    public void setTask(Component task) {
        this.task = Objects.requireNonNull(task, "Downloader task must not be null");
        this.percent = 0;
    }

    public Component getTask() {
        return task;
    }

    public void setProgress(float percent) {
        this.percent = percent;
    }

    public float getProgress() {
        return percent;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public boolean isDone() {
        return done;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }

    public boolean isFailed() {
        return failed;
    }

}
