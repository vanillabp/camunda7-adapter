package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the aggregate-sync integration test (story 28/28b): exactly ONE
 * attribute is shared with Camunda 7 - which since story 28b also DERIVES the
 * class' mode "share nothing else" (opt-in).
 * <p>
 * The gateway of {@code SyncProcess} branches on {@link #isApproved()}, an attribute
 * NOT shared: the embedded engine reads the aggregate LIVE through VanillaBP's EL
 * resolver, so a Camunda 7 expression works whether the attribute is shared or not.
 * Shared values are pure operator context (Cockpit).
 */
@Entity
@Table(name = "C7_SYNC_TEST_AGGREGATE")
@Getter
@Setter
public class SyncTestAggregate {

  /**
   * A HIGH id range on purpose: the Camunda business key is the aggregate's id, and
   * the id spaces of the different test aggregates would otherwise overlap - a
   * business-key query of another test would then match this test's workflows.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7SyncTestSeq")
  @jakarta.persistence.SequenceGenerator(name = "c7SyncTestSeq", initialValue = 900000, allocationSize = 1)
  private Long id;

  /**
   * The only attribute Camunda 7 gets to see - as operator context.
   */
  @io.vanillabp.spi.service.SyncWithBPMS
  private String customerName;

  private boolean approved;

  private String secret;

  private String taskId;

}
