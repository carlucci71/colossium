package it.daniele.colossium.repository;

import it.daniele.colossium.domain.News;
import it.daniele.colossium.domain.SearchCriteria;
import it.daniele.colossium.domain.Show;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Repository
public class RicercheRepository {

    @Autowired
    JdbcTemplate jdbcTemplate;

    public record SearchResult<T>(List<T> elements, int totElement) {
    }


    public SearchResult<Show> cercaShow(SearchCriteria searchCriteria) {
        /*
SELECT *, COUNT(*) OVER() as totElement FROM show WHERE 1=1
and (upper(titolo) like '%MORRICONE%' or upper(des) like '%MORRICONE%')
and upper(fonte) like '%COLOSSEO%'
and local_data ::date >= '2025-01-19'
and local_data ::date <= '2025-09-19'
and data_consegna ::date >= '2025-05-01'
and data_consegna ::date <= '2025-05-03'
ORDER BY id
LIMIT 10
OFFSET 0;
         */
        String sql = "SELECT *, COUNT(*) OVER() AS totElement from show WHERE 1=1 ";
        if (!ObjectUtils.isEmpty(searchCriteria.getTesto())) {
            sql = sql + " AND (upper(titolo) like '%" + searchCriteria.getTesto().toUpperCase() + "%' OR upper(des) like '%" + searchCriteria.getTesto().toUpperCase() + "%') ";
        }
        if (!ObjectUtils.isEmpty(searchCriteria.getFonte())) {
            sql = sql + " AND upper(fonte) like '%" + searchCriteria.getFonte().toUpperCase() + "%' ";
        }
        if (!ObjectUtils.isEmpty(searchCriteria.getDataMin())) {
            sql = sql + " AND local_data ::date >= '" + searchCriteria.getDataMin() + "' ";
        }
        if (!ObjectUtils.isEmpty(searchCriteria.getDataMax())) {
            sql = sql + " AND local_data ::date <= '" + searchCriteria.getDataMax() + "' ";
        }
        if (!ObjectUtils.isEmpty(searchCriteria.getDataConsegnaMin())) {
            sql = sql + " AND data_consegna ::date >= '" + searchCriteria.getDataConsegnaMin() + "' ";
        }
        if (!ObjectUtils.isEmpty(searchCriteria.getDataConsegnaMax())) {
            sql = sql + " AND data_consegna ::date <= '" + searchCriteria.getDataConsegnaMax() + "' ";
        }
        sql = sql + " ORDER BY id ";
        sql = sql + " LIMIT " + searchCriteria.getLimit();
        sql = sql + " OFFSET " + (searchCriteria.getPaginaCorrente() - 1) * searchCriteria.getLimit();
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .appendPattern("yyyy-MM-dd HH:mm:ss")
                .optionalStart()
                .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 1, 6, true)
                .optionalEnd()
                .toFormatter();
        AtomicInteger totElement = new AtomicInteger(0);
        List<Show> shows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Show show = new Show();
            totElement.set(rs.getInt("totElement"));
            show.setData(rs.getString("data"));
            show.setDataConsegna(LocalDateTime.parse(rs.getString("data_consegna"), formatter));
            show.setDes(rs.getString("des"));
            show.setFonte(rs.getString("fonte"));
            show.setFrom(rs.getString("da"));
            show.setHref(rs.getString("href"));
            show.setId(rs.getInt("id"));
            show.setImg(rs.getString("img"));
            show.setTitolo(rs.getString("titolo"));
            return show;
        });
        return new SearchResult<>(shows, totElement.get());
    }

    public SearchResult<News> cercaNews(SearchCriteria searchCriteria) {
        /*
SELECT *, COUNT(*) OVER() as totElement FROM news WHERE 1=1
and (upper(titolo) like '%MORRICONE%' or upper(des) like '%MORRICONE%')
and data_consegna ::date >= '2025-07-08'
and data_consegna ::date <= '2025-07-08'
order by id
LIMIT 10
OFFSET 0;
         */
        String sql = "SELECT *, COUNT(*) OVER() as totElement from news where 1=1 ";
        if (!ObjectUtils.isEmpty(searchCriteria.getTesto())) {
            sql = sql + " and (upper(titolo) like '%" + searchCriteria.getTesto().toUpperCase() + "%' or upper(des) like '%" + searchCriteria.getTesto().toUpperCase() + "%') ";
        }
        if (!ObjectUtils.isEmpty(searchCriteria.getDataConsegnaMin())) {
            sql = sql + " and data_consegna ::date >= '" + searchCriteria.getDataConsegnaMin() + "' ";
        }
        if (!ObjectUtils.isEmpty(searchCriteria.getDataConsegnaMax())) {
            sql = sql + " and data_consegna ::date <= '" + searchCriteria.getDataConsegnaMax() + "' ";
        }
        sql = sql + " order by id ";
        sql = sql + " LIMIT " + searchCriteria.getLimit();
        sql = sql + " OFFSET " + (searchCriteria.getPaginaCorrente() - 1) * searchCriteria.getLimit();
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .appendPattern("yyyy-MM-dd HH:mm:ss")
                .optionalStart()
                .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 1, 6, true)
                .optionalEnd()
                .toFormatter();
        AtomicInteger totElement = new AtomicInteger(0);
        List<News> elements = jdbcTemplate.query(sql, (rs, rowNum) -> {
            News news = new News();
            totElement.set(rs.getInt("totElement"));
            news.setDataConsegna(LocalDateTime.parse(rs.getString("data_consegna"), formatter));
            news.setDes(rs.getString("des"));
            news.setId(rs.getInt("id"));
            news.setTitolo(rs.getString("titolo"));
            return news;
        });
        return new SearchResult<>(elements, totElement.get());

    }

}
