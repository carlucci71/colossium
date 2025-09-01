
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

import static it.daniele.colossium.batch.JobConfig.CON_RECAP;
import static it.daniele.colossium.batch.JobConfig.TIPO_ELABORAZIONE;


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
                String conRecap="N";
                if (currentHour==10 || currentHour==13){
                    conRecap="S";
                }
                JobParameters jobParameters = new JobParametersBuilder()
                        .addLong("time", System.currentTimeMillis())
                        .addString(TIPO_ELABORAZIONE, JobConfig.TIPI_ELAB.ALL.name())
                        .addString(CON_RECAP, conRecap)
                        .toJobParameters();

                JobExecution jobExecution = jobLauncher.run(jobConfig.createJob(), jobParameters);

                logger.info("Batch job executed with status: {}", jobExecution.getStatus());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }


}
