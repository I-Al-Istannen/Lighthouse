package de.ialistannen.lighthouse.timing;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;
import de.ialistannen.lighthouse.notifier.Notifier;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CronRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(CronRunner.class);

  private final Cron cron;
  private final ExceptionalRunnable action;
  private final Notifier notifier;

  public CronRunner(Cron cron, Notifier notifier, ExceptionalRunnable action) {
    this.cron = cron;
    this.action = action;
    this.notifier = notifier;
  }

  /**
   * Runs the stored action on the cron schedule until eternity.
   */
  @SuppressWarnings("BusyWait")
  public void runUntilSingularity() {
    //noinspection InfiniteLoopStatement
    while (true) {
      Instant nextExecution = ExecutionTime.forCron(cron).nextExecution(ZonedDateTime.now()).orElseThrow().toInstant();

      LOGGER.info(
        "Sleeping until {} ({})",
        nextExecution,
        formatDurationHuman(Duration.between(Instant.now(), nextExecution))
      );

      try {
        while (nextExecution.isAfter(Instant.now())) {
          Duration between = Duration.between(Instant.now(), nextExecution);
          Thread.sleep(between.toSeconds() / 4 * 1000L);
        }

        action.run();
      } catch (Exception e) {
        notifier.notify(e);
      }
    }
  }

  private static String formatDurationHuman(Duration duration) {
    String result = "";
    if (duration.toHoursPart() > 0) {
      result += duration.toHoursPart() + " hours";
    }
    if (duration.toMinutesPart() > 0) {
      result += ", " + duration.toMinutesPart() + " minutes";
    }
    if (duration.toSecondsPart() > 0) {
      result += ", " + duration.toSecondsPart() + " seconds";
    }

    return result.replaceFirst("^,", "");
  }

}
