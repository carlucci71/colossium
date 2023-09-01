package it.daniele.colossium.domain;

import java.time.LocalDateTime;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Entity
public class Show {
	public Show() {
		super();
	}
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private int id;
	public Show(String data, String titolo, String img, String href) {
		super();
		this.data = data;
		this.titolo = titolo;
		this.img = img;
		this.href = href;
	}
	private String img;
	private String data;
	private String titolo;
	private String href;
	private LocalDateTime dataConsegna;
	public int getId() {
		return id;
	}
	public void setId(int id) {
		this.id = id;
	}
	public String getImg() {
		return img;
	}
	public void setImg(String img) {
		this.img = img;
	}
	public String getData() {
		return data;
	}
	public void setData(String data) {
		this.data = data;
	}
	public String getTitolo() {
		return titolo;
	}
	public void setTitolo(String titolo) {
		this.titolo = titolo;
	}
	public String getHref() {
		return href;
	}
	public void setHref(String href) {
		this.href = href;
	}
	@Override
	public String toString() {
		return "Show [img=" + img + ", data=" + data + ", titolo=" + titolo + ", href=" + href + "]";
	}
	public LocalDateTime getDataConsegna() {
		return dataConsegna;
	}
	public void setDataConsegna(LocalDateTime dataConsegna) {
		this.dataConsegna = dataConsegna;
	}

	
}
