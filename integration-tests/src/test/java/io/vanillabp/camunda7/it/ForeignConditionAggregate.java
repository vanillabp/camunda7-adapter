package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The JPA workflow aggregate of the condition-started process of the foreign-start
 * integration test. Its id is a String, so a business key somebody else chose can become
 * the id of the aggregate VanillaBP builds for that workflow.
 */
@Entity
@Table(name = "C7_FOREIGN_CONDITION_AGGREGATE")
@Getter
@Setter
public class ForeignConditionAggregate {

  @Id
  private String id;

  private String processedBy;

}
