package top.yogiczy.mytv.tv;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;

import java.util.logging.Logger;

public class MyJobService extends JobService {

    private Logger log = Logger.getLogger(MyJobService.class.getName());
    @Override
    public boolean onStartJob(JobParameters params) {
        log.info("onStartJob");
        // 在这里启动Activity
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        // 任务完成
        jobFinished(params, false);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}

