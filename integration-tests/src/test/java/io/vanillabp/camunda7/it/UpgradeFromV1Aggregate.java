package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the test about a workflow which version 1 started. Its id is set by the
 * test. The workflow already runs under that business key when version 2 boots, so the id
 * cannot come from a sequence.
 */
@Entity
@Table(name = "C7_UPGRADE_FROM_V1_TEST")
@Getter
@Setter
public class UpgradeFromV1Aggregate {

  /** The business key the workflow was started with in version 1. */
  @Id
  private String id;

  /** The events the user task was told about, in order, like "CANCELED". */
  private String reviewEvents = "";

  /** Whether the service task after the user task ran. */
  private boolean calculated;

  /** The iteration of the caller each run of the called process was told about, like "0/2,1/2". */
  private String iterations = "";

  /** The end event the workflow ended at, as the application was told. */
  private String endedAt;

  public void addReviewEvent(
      final String event) {

    reviewEvents = reviewEvents.isEmpty()
        ? event
        : reviewEvents
            + ","
            + event;

  }

  public void addIteration(
      final String iteration) {

    iterations = iterations.isEmpty()
        ? iteration
        : iterations
            + ","
            + iteration;

  }

}
