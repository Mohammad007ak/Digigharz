package ir.digigharz;

import ir.digigharz.web.ApiFilter;
import ir.digigharz.web.AppOptions;
import ir.digigharz.web.EnvConfig;
import ir.digigharz.web.Server;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/** One process serving the site, the app and the API. */
@SpringBootApplication
public class DigiGharz {
  @Bean
  FilterRegistrationBean<ApiFilter> apiFilter() {
    FilterRegistrationBean<ApiFilter> bean = new FilterRegistrationBean<>(new ApiFilter());
    bean.addUrlPatterns("/api/*", "/api");
    return bean;
  }

  /** Starts a server built from these options; port 0 picks a free one. */
  public static ConfigurableApplicationContext start(AppOptions options) {
    Server server = new Server(options);
    SpringApplication app = new SpringApplication(DigiGharz.class);
    app.setDefaultProperties(Map.of(
        "server.port", String.valueOf(options.port),
        "server.shutdown", "graceful",
        "server.error.whitelabel.enabled", "false",
        "spring.web.resources.add-mappings", "false",
        "spring.main.banner-mode", "off",
        "spring.jmx.enabled", "false",
        // Small container (a quarter core, 512 MB): a modest thread pool is plenty.
        "server.tomcat.threads.max", "50"));
    app.addInitializers(ctx -> ctx.getBeanFactory().registerSingleton("server", server));
    ConfigurableApplicationContext context = app.run();
    if (options.scheduler) {
      // Draws run on their scheduled day (see lib/Schedule).
      ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "scheduled-draws");
        t.setDaemon(true);
        return t;
      });
      timer.scheduleWithFixedDelay(() -> {
        try {
          server.circles.runDueDraws();
        } catch (RuntimeException e) {
          e.printStackTrace();
        }
      }, 60, 60, TimeUnit.SECONDS);
      context.addApplicationListener(e -> {
        if (e instanceof org.springframework.context.event.ContextClosedEvent) timer.shutdownNow();
      });
    }
    return context;
  }

  public static void main(String[] args) {
    // A setting that would make the server unsafe or broken stops it here;
    // say which one in a single line that's easy to spot in the host's logs.
    AppOptions options;
    try {
      options = EnvConfig.fromEnv(EnvConfig.environment());
      new Server(options); // the same checks the server makes, before Spring starts
    } catch (RuntimeException e) {
      System.err.println("\n❌ Server did not start / سرور بالا نیامد: " + e.getMessage() + "\n");
      System.exit(1);
      return;
    }
    start(options);
    System.out.println("Digi Gharz listening on port " + options.port);
  }
}
