package it.daniele.colossium.domain;

public class SearchCriteria {
    private String testo="";
    private String fonte="";
    private String dataMin = "2021-01-01";
    private String dataMax = "2030-01-01";
    private String dataConsegnaMin = "2021-01-01";
    private String dataConsegnaMax = "2030-01-01";
    private Integer limit = LIMIT_DEFAULT;
    public static final String DATA_DEFAULT_MIN = "2020-01-01";
    public static final String DATA_DEFAULT_MAX = "2050-01-01";

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public String getTesto() {
        return testo;
    }

    public void setTesto(String testo) {
        this.testo = testo;
    }

    public String getFonte() {
        return fonte;
    }

    public void setFonte(String fonte) {
        this.fonte = fonte;
    }

    public String getDataMin() {
        return dataMin;
    }

    public void setDataMin(String dataMin) {
        this.dataMin = dataMin;
    }

    public String getDataMax() {
        return dataMax;
    }

    public void setDataMax(String dataMax) {
        this.dataMax = dataMax;
    }

    public String getDataConsegnaMin() {
        return dataConsegnaMin;
    }

    public void setDataConsegnaMin(String dataConsegnaMin) {
        this.dataConsegnaMin = dataConsegnaMin;
    }

    public String getDataConsegnaMax() {
        return dataConsegnaMax;
    }

    public void setDataConsegnaMax(String dataConsegnaMax) {
        this.dataConsegnaMax = dataConsegnaMax;
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("SearchCriteria{");
        sb.append("testo='").append(testo).append('\'');
        sb.append(", fonte='").append(fonte).append('\'');
        sb.append(", dataMin='").append(dataMin).append('\'');
        sb.append(", dataMax='").append(dataMax).append('\'');
        sb.append(", dataConsegnaMin='").append(dataConsegnaMin).append('\'');
        sb.append(", dataConsegnaMax='").append(dataConsegnaMax).append('\'');
        sb.append(", limit=").append(limit);
        sb.append('}');
        return sb.toString();
    }

    public static final Integer LIMIT_DEFAULT = 25;
    public enum FiltriRicerca {
        TESTO("Testo"),
        FONTE("Fonte"),
        DATA_MIN("Data spettacolo minima"),
        DATA_MAX("Data spettacolo massima"),
        DATA_CONSEGNA_MIN("Data notifica minima"),
        DATA_CONSEGNA_MAX("Data notifica massima"),
        LIMIT("Numero elementi ricerca");

        private String des;

        FiltriRicerca(String des) {
            this.des = des;
        }

        public String getDes() {
            return des;
        }
    }
}
