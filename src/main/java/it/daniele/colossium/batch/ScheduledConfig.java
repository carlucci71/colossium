
package it.daniele.colossium.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;


@Configuration
public class ScheduledConfig {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobConfig jobConfig;

    Logger logger = LoggerFactory.getLogger(this.getClass());

    @Scheduled(cron = "0 0 * * * ?")//OGNI ORA
    public void runBatchJob() {
        LocalDateTime localDateTime = LocalDateTime.now();
        int currentHour = localDateTime.getHour();
        if (currentHour > 8 && currentHour < 17) {
            try {
                JobParameters jobParameters = new JobParametersBuilder()
                        .addLong("time", System.currentTimeMillis())
                        .addString("tipoElaborazione", JobConfig.TIPI_ELAB.ALL.name())
                        .toJobParameters();

                JobExecution jobExecution = jobLauncher.run(jobConfig.createJob(), jobParameters);

                logger.info("Batch job executed with status: {}", jobExecution.getStatus());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }


}
