package it.daniele.colossium.repository;

import it.daniele.colossium.domain.Logga;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public interface LoggaRepository extends CrudRepository<Logga, String> {

}
