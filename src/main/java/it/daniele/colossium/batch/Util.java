package it.daniele.colossium.batch;

import it.daniele.colossium.domain.Logga;
import it.daniele.colossium.repository.LoggaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

@Component
public class Util {
    Logger logger = LoggerFactory.getLogger(this.getClass());
    @Autowired
    LoggaRepository loggaRepository;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void loggaEccezione(List<Throwable> e) {
        try {
            for (Throwable throwable : e) {
                logger.error(throwable.getMessage(), e);
                Logga logga = new Logga();
                logga.setData(new Timestamp(new Date().getTime()));
                logga.setLog(throwable.getMessage());
                loggaRepository.save(logga);
            }
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

}
