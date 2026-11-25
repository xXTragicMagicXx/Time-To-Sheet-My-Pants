package io.github.xxtragicmagicxx.timesheet;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** About once a day, while the phone is online: look for a newer app version and, if the app is closed, say so. */
public class UpdateJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters p) {
        new Thread(() -> {
            try {
                Updater.Info i = Updater.check();
                Updater.save(this, i);
                if (i != null && !MainActivity.visible) Notif.update(this, i.ver);
            } catch (Exception ignored) {
            }
            jobFinished(p, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        return false;
    }
}
