package it.daniele.colossium.domain;

import java.time.LocalDateTime;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Entity
public class News {
	public News() {
		super();
	}
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private int id;
	public News(String titolo, String des) {
		super();
		this.titolo = titolo;
		this.des = des;
	}
	private String titolo;
	private String des;
	private LocalDateTime dataConsegna;
	public int getId() {
		return id;
	}
	public void setId(int id) {
		this.id = id;
	}
	public String getTitolo() {
		return titolo;
	}
	public void setTitolo(String titolo) {
		this.titolo = titolo;
	}
	public String getDes() {
		return des;
	}
	public void setDes(String des) {
		this.des = des;
	}
	public LocalDateTime getDataConsegna() {
		return dataConsegna;
	}
	public void setDataConsegna(LocalDateTime dataConsegna) {
		this.dataConsegna = dataConsegna;
	}
	@Override
	public String toString() {
		return titolo + "\n\r\n\r" + des;
	}
	
}
