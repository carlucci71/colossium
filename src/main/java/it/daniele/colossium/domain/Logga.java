package it.daniele.colossium.domain;


import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Lob;
import java.sql.Timestamp;

import static javax.persistence.GenerationType.IDENTITY;

@Entity
public class Logga {
    @Id @GeneratedValue(strategy=IDENTITY)
    private Integer id;
	private Timestamp data;
	@Lob
	@Column(columnDefinition = "text")
	private String log;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Timestamp getData() {
        return data;
    }

    public void setData(Timestamp data) {
        this.data = data;
    }

    public String getLog() {
        return log;
    }

    public void setLog(String log) {
        this.log = log;
    }


}
