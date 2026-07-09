package io.vanillabp.camunda7.springboot.deployment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.vanillabp.camunda7.Camunda7ProcessingContext;
import io.vanillabp.camunda7.deployment.Camunda7DeploymentService;
import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineConfiguration;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;

/**
 * Builds one {@link Camunda7DeploymentService} per configured adapter id of type
 * {@value Camunda7AdapterConfiguration#ADAPTER_TYPE}. The same BPMS type may be
 * configured several times (e.g. two Camunda 7 engines side by side during a migration),
 * so deployment services exist per adapter id, not per type.
 */
@AutoConfiguration(after = {
    SpringBootMigrationAdapterAutoConfiguration.class, Camunda7EngineConfiguration.class
})
public class Camunda7DeploymentConfiguration {

  @Bean
  public List<AdapterDeploymentService<BpmnModelInstance, Camunda7ProcessingContext>> camunda7DeploymentServices(
      final WorkflowModules allWorkflowModules,
      final MigrationAdapterProperties properties,
      final ObjectProvider<RepositoryService> repositoryService) {

    // resolved here (not injected directly) so the discovery-only smoke test - which does
    // not wire an embedded engine - can still build this list bean; the core deployment
    // service fails with a clear message if it is actually used without an engine
    final var camunda7RepositoryService = repositoryService.getIfAvailable();

    final List<AdapterDeploymentService<BpmnModelInstance, Camunda7ProcessingContext>> deploymentServices = new ArrayList<>();
    final Set<String> adaptersBuilt = new HashSet<>();

    // walk through all workflow modules
    allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        // for each adapter configured...
        .forEach(workflowModuleId -> properties
            .getPrioritizedAdaptersFor(workflowModuleId)
            .stream()
            // ...find adapters of the Camunda 7 type...
            .filter(adapterId -> properties
                .getAdapters()
                .get(adapterId)
                .equals(Camunda7AdapterConfiguration.ADAPTER_TYPE))
            .forEach(adapterId -> {

              // avoid building the same adapter more than once
              if (adaptersBuilt.contains(adapterId)) {
                return;
              }

              deploymentServices.add(new Camunda7DeploymentService(adapterId, camunda7RepositoryService));
              adaptersBuilt.add(adapterId);

            }));

    return deploymentServices;

  }

}
