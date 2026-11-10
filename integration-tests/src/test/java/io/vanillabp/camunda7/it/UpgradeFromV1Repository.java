package io.vanillabp.camunda7.it;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UpgradeFromV1Repository extends JpaRepository<UpgradeFromV1Aggregate, String> {

}
