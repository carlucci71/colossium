package it.daniele.colossium;

import it.daniele.colossium.batch.JobConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ColossiumApplication {

	public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(ColossiumApplication.class, args);

//        ScheduledConfig scheduledConfig = ctx.getBean(ScheduledConfig.class);
//        scheduledConfig.runBatchJob();

        JobConfig jobConfig= ctx.getBean(JobConfig.class);
        for (JobConfig.Fonti fonte : JobConfig.Fonti.values()) {
            jobConfig.gimmi(fonte);
        }
	}

}
