package it.daniele.colossium.domain;

import java.time.LocalDateTime;

import javax.persistence.Entity;
import javax.persistence.Id;

@Entity
public class TelegramMsg {
	public TelegramMsg() {
		super();
		// TODO Auto-generated constructor stub
	}
	@Id
	private int id;
	private LocalDateTime dataConsegna;
	private LocalDateTime dataEliminazione;
	public TelegramMsg(int id, LocalDateTime dataConsegna) {
		super();
		this.id = id;
		this.dataConsegna = dataConsegna;
	}
	public LocalDateTime getDataConsegna() {
		return dataConsegna;
	}
	public void setDataConsegna(LocalDateTime dataConsegna) {
		this.dataConsegna = dataConsegna;
	}
	public int getId() {
		return id;
	}
	public void setId(int id) {
		this.id = id;
	}
	public LocalDateTime getDataEliminazione() {
		return dataEliminazione;
	}
	public void setDataEliminazione(LocalDateTime dataEliminazione) {
		this.dataEliminazione = dataEliminazione;
	}
	@Override
	public String toString() {
		return "TelegramMsg [id=" + id + ", dataConsegna=" + dataConsegna + ", dataEliminazione=" + dataEliminazione
				+ "]";
	}
	
	
}
