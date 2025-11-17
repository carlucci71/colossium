package it.daniele.colossium;

import it.daniele.colossium.batch.ScheduledConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ColossiumApplication {

	public static void main(String[] args) {
        ConfigurableApplicationContext run = SpringApplication.run(ColossiumApplication.class, args);
        ScheduledConfig scheduledConfig = run.getBean(ScheduledConfig.class);
        scheduledConfig.runBatchJob("S");
    }

}
