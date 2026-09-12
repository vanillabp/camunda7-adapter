package io.vanillabp.camunda7.engine;

import org.camunda.bpm.engine.impl.jobexecutor.JobAcquisitionStrategy;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.camunda.bpm.engine.impl.jobexecutor.SequentialJobAcquisitionRunnable;

/**
 * The acquisition loop of an executor which sleeps until something is due. The engine
 * decides the waiting rule in the strategy its loop creates, and creating that strategy
 * is the only reason this class exists.
 * <p>
 * It also remembers which thread runs the loop. The loop's own acquisition commits like
 * any other engine command, so a wake-up hung off every commit would wake the loop from
 * inside itself and the sleep would never happen. {@link Camunda7WakeupAfterCommit} asks
 * here instead of guessing from a thread name.
 */
public class Camunda7SleepingAcquisition extends SequentialJobAcquisitionRunnable {

  private final String adapterId;

  /**
   * The thread running the acquisition cycles, set while the loop runs and
   * <code>null</code> otherwise.
   */
  private volatile Thread acquisitionThread;

  public Camunda7SleepingAcquisition(
      final String adapterId,
      final JobExecutor jobExecutor) {

    super(jobExecutor);
    this.adapterId = adapterId;

  }

  @Override
  public void run() {

    acquisitionThread = Thread.currentThread();
    try {
      super.run();
    } finally {
      acquisitionThread = null;
    }

  }

  @Override
  protected JobAcquisitionStrategy initializeAcquisitionStrategy() {

    return new Camunda7SleepUntilSomethingIsDue(adapterId, jobExecutor);

  }

  /**
   * @param thread A thread about to commit something
   * @return Whether it is the thread running the acquisition cycles
   */
  public boolean runsTheAcquisition(
      final Thread thread) {

    return (acquisitionThread != null) && (acquisitionThread == thread);

  }

}
