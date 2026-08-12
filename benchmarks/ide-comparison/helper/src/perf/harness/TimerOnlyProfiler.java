package perf.harness;

import com.intellij.openapi.project.Project;
import com.jetbrains.performancePlugin.Timer;
import com.jetbrains.performancePlugin.profilers.Profiler;
import com.jetbrains.performancePlugin.profilers.ProfilersController;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A performanceTesting profiler backend that only runs JetBrains' Timer.
 *
 * The bundled async backend is disabled by -DintegrationTests.profiler=timer,
 * so measured caret timings do not include async-profiler sampling or JFR
 * shutdown/conversion work.
 */
public final class TimerOnlyProfiler implements Profiler {
    private final AtomicReference<String> runningActivity = new AtomicReference<>();

    @Override
    public void startProfiling(String activityName, List<String> options) {
        if (!runningActivity.compareAndSet(null, activityName)) {
            throw new IllegalStateException("timer-only profiler is already running");
        }
        Timer.instance.start(activityName, false);
        ProfilersController.getInstance().setCurrentProfiler(this);
    }

    @Override
    public String stopProfiling(List<String> options) {
        return stopTimer();
    }

    @Override
    public String stopProfileWithNotification(String arguments) {
        return stopTimer();
    }

    private String stopTimer() {
        if (runningActivity.getAndSet(null) == null) {
            return null;
        }
        if (Timer.instance.isStarted()) {
            Timer.instance.stop();
            Timer.instance.reportToTeamCity();
        }
        return null;
    }

    @Override
    public File compressResults(String pathToResult, String archiveName) throws IOException {
        return new File(pathToResult);
    }

    @Override
    public boolean isEnabled() {
        return "timer".equals(System.getProperty("integrationTests.profiler"));
    }

    @Override
    public boolean isEnabledInProject(Project project) {
        return isEnabled();
    }

    @Override
    public boolean isProfilingStarted() {
        return runningActivity.get() != null;
    }
}
