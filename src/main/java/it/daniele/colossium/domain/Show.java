package it.daniele.colossium.domain;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Entity
public class Show {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private int id;
	private String img;
	private String des;
	private String data;
	private LocalDateTime localData;
    private String titolo;
	private String fonte;
	@Column(name = "da")
	private String from;
	private String href;
	private LocalDateTime dataConsegna;
	public Show() {
		super();
	}
	public Show(String data, String titolo, String img, String href, String des, String fonte, String from, LocalDateTime localData) {
		super();
		this.data = data;
		this.setDes(des);
		this.titolo = titolo;
		this.img = img;
		this.fonte=fonte;
		this.href = href;
		this.from = from;
        this.localData=localData;
	}

    public LocalDateTime getLocalData() {
        return localData;
    }

    public void setLocalData(LocalDateTime localData) {
        this.localData = localData;
    }

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
		return data + "\n\r" + titolo + "\n\r" + (href==null?"":href) + "\n\r\n\r\n\r" + from;
	}
	public LocalDateTime getDataConsegna() {
		return dataConsegna;
	}
	public void setDataConsegna(LocalDateTime dataConsegna) {
		this.dataConsegna = dataConsegna;
	}
	public String getFonte() {
		return fonte;
	}
	public void setFonte(String fonte) {
		this.fonte = fonte;
	}
	public String getDes() {
		return des;
	}
	public void setDes(String des) {
		this.des = des;
	}
	public String getFrom() {
		return from;
	}
	public void setFrom(String from) {
		this.from = from;
	}

	
}
