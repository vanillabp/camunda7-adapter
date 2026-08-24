package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The JPA workflow aggregate of the timer-start integration test. Its ID is a
 * String, so the timer's trigger time can be its identity - and no
 * <code>@GeneratedValue</code> is involved, which proves VanillaBP assigns the ID
 * itself for a workflow nobody started through the {@code ProcessService}.
 */
@Entity
@Table(name = "C7_TIMER_START_AGGREGATE")
@Getter
@Setter
public class TimerStartTestAggregate {

  @Id
  private String id;

  private String processedBy;

  /**
   * Set by the <code>@WorkflowEnded</code> method.
   */
  private String endedAs;

}
