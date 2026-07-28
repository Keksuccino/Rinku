package de.keksuccino.mcef.internal;

import org.spongepowered.asm.mixin.Unique;

public class MCEFDownloadListener {
    // TODO: I kinda would like to keep other mods from accessing this, but mixin complicates stuff
    @Unique
    public static final MCEFDownloadListener INSTANCE = new MCEFDownloadListener();

    // The installer runs on MCEF-Downloader while the render thread polls this state. Volatile
    // publication keeps progress and the terminal state visible without tying either thread to a
    // UI lock during network and extraction work.
    private volatile String task;
    private volatile float percent;
    private volatile boolean done;
    private volatile boolean failed;

    public void setTask(String name) {
        this.task = name;
        this.percent = 0;
    }

    public String getTask() {
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
