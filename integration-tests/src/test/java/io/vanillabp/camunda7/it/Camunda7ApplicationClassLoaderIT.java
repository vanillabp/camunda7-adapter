package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import javax.tools.ToolProvider;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.io.DefaultResourceLoader;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The engine finds a class of the application, although the calling thread cannot see it.
 * This is how Spring DevTools runs an application: its classes come from a class loader of
 * their own, while a thread of a library still has the system class loader. The engine
 * loads a <code>camunda:class</code> through its own class loader first, then through the
 * class loader of the calling thread.
 * <p>
 * The test builds that situation. It compiles a delegate into a directory which is not on the
 * class path, and it starts the application with a class loader which sees that directory.
 * Then a thread whose class loader is the system class loader starts a process which names the
 * delegate. Without the class loader of the application context the engine would not find
 * the delegate, and the start would fail with a {@code ClassNotFoundException}.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7ApplicationClassLoaderIT {

  private static final String DELEGATE = "hidden.HiddenDelegate";

  private static final String PROCESS = "ApplicationClassLoaderProcess";

  @TempDir
  static Path classes;

  @Test
  @DisplayName("a camunda:class of the application is found from a thread with the system class loader")
  public void aClassOfTheApplicationIsFoundFromAForeignThread() throws Exception {

    compileTheDelegate();
    final var applicationClassLoader = new URLClassLoader(new URL[]{
        classes
            .toUri()
            .toURL()
    }, Camunda7ApplicationClassLoaderIT.class.getClassLoader());
    final var systemClassLoader = ClassLoader.getSystemClassLoader();
    // the precondition: the thread below cannot see the delegate
    assertThrows(ClassNotFoundException.class, () -> systemClassLoader.loadClass(DELEGATE));

    try (applicationClassLoader; var context = new SpringApplicationBuilder(TestApplication.class)
        .resourceLoader(new DefaultResourceLoader(applicationClassLoader))
        .run("--spring.datasource.url=jdbc:h2:mem:c7-application-class-loader;DB_CLOSE_DELAY=-1")) {
      final var engine = context.getBean(ProcessEngine.class);
      assertSame(applicationClassLoader, ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
          .getClassLoader());
      deployTheProcess(engine);

      final var started = new AtomicReference<Object>();
      final var thread = new Thread(() -> {
        try {
          started.set(engine
              .getRuntimeService()
              .startProcessInstanceByKey(PROCESS)
              .getId());
        } catch (final RuntimeException e) {
          started.set(e);
        }
      });
      thread.setContextClassLoader(systemClassLoader);
      thread.start();
      thread.join();

      final var processInstanceId = assertInstanceOfString(started.get());
      assertEquals(DELEGATE, engine
          .getRuntimeService()
          .getVariable(processInstanceId, "calledBy"));
    }

  }

  private static String assertInstanceOfString(
      final Object started) {

    if (started instanceof RuntimeException failure) {
      throw new AssertionError("the start failed", failure);
    }
    assertTrue(started instanceof String, String.valueOf(started));
    return (String) started;

  }

  /** A process nobody claims, deployed without a tenant. VanillaBP leaves it as modelled. */
  private static void deployTheProcess(
      final ProcessEngine engine) {

    engine
        .getRepositoryService()
        .createDeployment()
        .addModelInstance(
            "application-class-loader.bpmn",
            Bpmn
                .createExecutableProcess(PROCESS)
                .camundaHistoryTimeToLive(1)
                .startEvent()
                .serviceTask("callTheDelegate")
                .camundaClass(DELEGATE)
                .userTask("waitHere")
                .endEvent()
                .done())
        .deploy();

  }

  private static void compileTheDelegate() throws IOException, URISyntaxException {

    final var source = classes.resolve("HiddenDelegate.java");
    Files
        .writeString(
            source,
            """
                package hidden;

                public class HiddenDelegate implements org.camunda.bpm.engine.delegate.JavaDelegate {

                  @Override
                  public void execute(org.camunda.bpm.engine.delegate.DelegateExecution execution) {
                    execution.setVariable("calledBy", getClass().getName());
                  }

                }
                """);
    final var engineJar = Path
        .of(JavaDelegate.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .toURI())
        .toString();
    final var result = ToolProvider
        .getSystemJavaCompiler()
        .run(null, null, null, "-classpath", engineJar, "-d", classes.toString(), source.toString());
    assertEquals(0, result, "the delegate did not compile");

  }

}
