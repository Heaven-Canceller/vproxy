package io.github.heavencanceller.vproxy;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;

public final class RefreshJobService extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Future<?> task;
    static void schedule(Context context, boolean enabled, int minutes) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if (!enabled) { scheduler.cancel(2002); return; }
        JobInfo job=new JobInfo.Builder(2002,new ComponentName(context,RefreshJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(Math.max(15,minutes)*60_000L).setPersisted(false).build();
        scheduler.schedule(job);
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        task=worker.submit(() -> {
            boolean retry=false;
            try { CoreClient.call(this,"POST","/android/tick",null); }
            catch (Exception error) { retry=true; }
            jobFinished(parameters,retry);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) { if(task!=null) task.cancel(true); return true; }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
