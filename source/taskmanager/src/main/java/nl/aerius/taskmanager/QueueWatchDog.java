/*
 * Copyright the State of the Netherlands
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 */
package nl.aerius.taskmanager;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * WatchDog to detect dead messages. Dead messages are messages once put on the queue, but those messages have gone. For example because
 * the queue was purged after some restart. In such a case the scheduler keeps the tasks locked and since there will never come an message
 * for the task it's locked indefinitely. This watch dog tries to detect such tasks and release them at some point.
 */
class QueueWatchDog {

  /**
   * If for more than 10 minutes the problem remains the sign to reset is given.
   */
  private static final long RESET_TIME = 10;

  private long firstProblem;

  /**
   * Check if the condition is met to do a reset. This is if for more than {@link #RESET_TIME} minutes workers are running,
   * but no messages were on the queue it's time to free all tasks.
   * @param runningWorkers number of workers running
   * @param numberOfMessages number of messages on queue
   * @return true if it's time to free all tasks
   */
  public boolean isItDead(final boolean runningWorkers, final int numberOfMessages) {
    boolean doReset = false;
    if (runningWorkers && numberOfMessages == 0) {
      if (firstProblem == 0) {
        firstProblem = new Date().getTime();
      } else {
        doReset = calculatedDiffTime(new Date().getTime() - firstProblem) > RESET_TIME;
      }
    } else {
      firstProblem = 0;
    }
    return doReset;
  }

  /**
   * Convinces method for testing, because the time unit is in minutes, testing would take forever.
   * @param diff time differences in milliseconds.
   * @return time differences in minutes
   */
  protected long calculatedDiffTime(final long diff) {
    return TimeUnit.MILLISECONDS.toMinutes(diff);
  }
}
