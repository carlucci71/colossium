package it.daniele.colossium.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.daniele.colossium.domain.Logga;
import it.daniele.colossium.domain.News;
import it.daniele.colossium.domain.Show;
import it.daniele.colossium.domain.TelegramMsg;
import it.daniele.colossium.repository.LoggaRepository;
import it.daniele.colossium.telegrambot.TelegramBot;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import secrets.ConstantColossium;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@SuppressWarnings("deprecation")
@Configuration
@EnableBatchProcessing
public class JobConfig {
    public static final String TIPO_ELABORAZIONE = "tipoElaborazione";
    public static final String CON_RECAP = "conRecap";
    RestTemplate restTemplate = new RestTemplate();
    Logger logger = LoggerFactory.getLogger(this.getClass());

    @PersistenceContext
    EntityManager entityManager;

    @Autowired
    TelegramBot telegramBot;

    @Autowired
    LoggaRepository loggaRepository;

    @Autowired
    StepBuilderFactory stepBuilderFactory;

    @Autowired
    JobBuilderFactory jobBuilderFactory;


    //@Bean
    public Job createJob() {
        return jobBuilderFactory.get("MyJob")
                .incrementer(new RunIdIncrementer())
                .listener(jobResultListener())
                .start(stepInit())
                .next(stepNews())
                .next(stepShow())
                .build();
    }

    enum TIPI_ELAB {NEWS_COLOSSEO, SHOW_COLOSSEO, TICKET_ONE, SALONE_LIBRO, CONCORDIA, VIVATICKET, DICE, TICKET_MASTER, MAIL_TICKET, EVENTBRITE, ALL}

    TIPI_ELAB tipoElaborazione;


    private JobExecutionListener jobResultListener() {
        return new JobExecutionListener() {
            public void beforeJob(JobExecution jobExecution) {
                tipoElaborazione = TIPI_ELAB.valueOf(jobExecution.getJobParameters().getString(TIPO_ELABORAZIONE));
                logger.debug("Called beforeJob: " + tipoElaborazione);
                totShows = new HashMap<>();
                totNewShows = new HashMap<>();
                messaggiInviati = 0;
                listNews = new ArrayList<>();
                listShow = new ArrayList<>();
                contaEventi = 0;
                posizioneNews = 0;
                posizioneShow = 0;
                esito = "";
                skipped = new ArrayList<>();
            }

            public void afterJob(JobExecution jobExecution) {
                String conRecap = jobExecution.getJobParameters().getString(CON_RECAP);
                if (jobExecution.getStatus() == BatchStatus.COMPLETED
                        && (
                        "S".equals(conRecap)
                                || (messaggiInviati + totNewShows.size() > 0)
                )
                ) {
                    logger.info(totNewShows.toString());
                    telegramBot.inviaMessaggio("(" + contaEventi + ")\n" +
                            "skipped: " + skipped + "\n" +
                            "nuove news: " + messaggiInviati + "\n" +
                            "nuovi show:" + totNewShows + "\n\n" +
                            esito +
                            "processati: " + totShows
                    );
                    logger.info("COMPLETED: {}", jobExecution);
                } else if (jobExecution.getStatus() == BatchStatus.FAILED) {
                    telegramBot.inviaMessaggio("ERRORE" + jobExecution.getAllFailureExceptions());
                    loggaEccezione(jobExecution.getFailureExceptions());
                    logger.info("FAILED: {}", jobExecution);
                } else if (skipped.size() > 0) {
                    telegramBot.inviaMessaggio("SKIPPED" + skipped);
                    logger.info("SKIPPER: {}", skipped);
                }
            }
        };
    }

    private StepExecutionListener stepResultListener() {

        return new StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution stepExecution) {
                logger.debug("Called beforeStep: {}", stepExecution);
            }

            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                logger.info("Called afterStep: {}", stepExecution);
                if (!stepExecution.getStepName().equals("stepInit")) {
                    esito = esito + stepExecution.getStepName() + ":" + stepExecution.getWriteCount() + "\n\r";
                }
                return null;
            }
        };
    }

    private Step stepInit() {
        return stepBuilderFactory.get("stepInit")
                .tasklet((contribution, chunkContext) -> {
                    leggiNewsColosseo();
                    leggiShowColosseo();
                    leggiTicketOne();
                    leggiConcordia();
                    leggiVivaTicket();
                    leggiDice();
                    leggiTicketMaster();
                    leggiMailTicket();
                    leggiEventbrite();
                    cancellaNotificheTelegramScadute();
                    return RepeatStatus.FINISHED;
                })
                .listener(stepResultListener())
                .build();
    }

    private void leggiEventbrite() {
        String fonte = "Eventbrite";
        try {
            if (tipoElaborazione == TIPI_ELAB.ALL || tipoElaborazione == TIPI_ELAB.EVENTBRITE) {
                int showIniziali = listShow.size();
                Integer page = 1;
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ITALIAN);
                do {
                    String from = "https://www.eventbrite.it/d/italy--torino/all-events/?page=" + page;
                    String html;
                    try {
                        html = restTemplate.getForObject(from, String.class);
                        String tokenInizio = "window.__SERVER_DATA__ = ";
                        html = html.substring(html.indexOf(tokenInizio) + tokenInizio.length());
                        String tokenFine = "};";
                        html = html.substring(0, html.indexOf(tokenFine) + 1).trim();
                        Map<String, Object> map = jsonToMap(html);
                        List<Map<String, Object>> l = (List<Map<String, Object>>) map.get("jsonld");
                        Map<String, Object> map2 = l.get(0);
                        List<Map<String, Object>> listaElem = (List<Map<String, Object>>) map2.get("itemListElement");
                        if (listaElem == null) {
                            page = null;
                        } else {
                            for (int i = 0; i < listaElem.size(); i++) {
                                Map<String, Object> item = (Map<String, Object>) listaElem.get(i).get("item");
                                try {
                                    String data = "";
                                    String titolo = "";
                                    String img = "";
                                    String href = "";
                                    String des = "";

                                    try {
                                        data = item.get("startDate").toString();
                                    } catch (Exception e) {
                                    }
                                    try {
                                        titolo = item.get("name").toString();
                                    } catch (Exception e) {
                                    }
                                    try {
                                        img = item.get("image").toString();
                                    } catch (Exception e) {
                                    }
                                    try {
                                        href = item.get("url").toString();
                                    } catch (Exception e) {
                                    }
                                    try {
                                        des = item.get("description").toString();
                                        try {
                                            String dataFine = item.get("startDate").toString();
                                            if (!data.equals(dataFine)) {
                                                des = des + "\nDal " + data + " al " + dataFine;
                                            }
                                            Map<String, Object> location = (Map<String, Object>) item.get("location");
                                            des = des + "\nLocation:" + location.get("name");
                                            Map<String, Object> address = (Map<String, Object>) location.get("address");
                                            des = des + "\nAddress:" + address.get("streetAddress") + " " + address.get("addressLocality");
                                        } catch (Exception e) {
                                        }

                                    } catch (Exception e) {
                                    }

                                    LocalDateTime ld;
                                    try {
                                        ld = LocalDate.parse(data, formatter).atStartOfDay();
                                    } catch (Exception e) {
                                        ld = LocalDateTime.now();
                                    }


                                    Show show = new Show(data, titolo, img, href, des, fonte, from, ld);
                                    listShow.add(show);
                                } catch (Exception e) {
                                }
                            }
                            page++;
                        }

                    } catch (Exception e) {
                        loggaEccezione(List.of(e));
                    }
//                    page++;
                } while (page != null);
                totShows.put(fonte, listShow.size() - showIniziali);
            }
        } catch (RuntimeException e) {
            skipped.add(fonte);
            loggaEccezione(List.of(e));
        }
    }


    private void cancellaNotificheTelegramScadute() {
        List<TelegramMsg> resultList = entityManager.createQuery("select t from TelegramMsg t where dataEliminazione is null", TelegramMsg.class).getResultList();
        resultList.forEach(el -> {
            if (LocalDateTime.now().isAfter(el.getDataConsegna().plusDays(ConstantColossium.DAY_TTL))) {
                el.setDataEliminazione(LocalDateTime.now());
                DeleteMessage deleteMessage = new DeleteMessage(ConstantColossium.MY_CHAT_ID, el.getId());
                try {
                    entityManager.persist(el);
                    telegramBot.execute(deleteMessage);
                } catch (TelegramApiException e) {
                    loggaEccezione(List.of(e));
                    esito = esito + "WARNING CANCELLA NOTIFICHE\n\r";
                }
            }
        });
    }

    private void leggiShowColosseo() {
        String fonte = "COLOSSEO";
        try {
            if (tipoElaborazione == TIPI_ELAB.ALL || tipoElaborazione == TIPI_ELAB.SHOW_COLOSSEO) {
                int showIniziali = listShow.size();
                String from = "https://api.teatrocolosseo.it/api/spettacoli";
                List<Map<String, Object>> response;
                try {
                    response = restTemplate.getForObject(from, List.class);
                } catch (Exception e) {
                    throw new RuntimeException("Errore chiamando: " + from + "\n" + e.getMessage());
                }
                for (int i = 0; i < response.size(); i++) {
                    Map<String, Object> element = response.get(i);
                    try {
                        String data = "";
                        String titolo = "";
                        String img = "";
                        String href = "";
                        String des = "";

                        Integer id = (Integer) element.get("id");

                        try {
                            data = element.get("dal").toString();
                        } catch (Exception e) {
                            data = "Eccezione in: " + id + " dal ";
                        }

                        try {
                            titolo = element.get("spettacolo").toString() + " - " + (element.get("compagnia") == null ? "" : element.get("compagnia").toString());
                        } catch (Exception e) {
                            titolo = "Eccezione in: " + id + " spettacolo ";
                        }

                        try {
                            img = "https://api.teatrocolosseo.it/api/image/" + element.get("img_copertina").toString() + "?type=spettacolo";
                        } catch (Exception e) {
                            loggaEccezione(List.of(new Exception("Eccezione in: " + id + " image ")));
                        }

                        try {
                            href = element.get("link_webshop") == null ? "" : element.get("link_webshop").toString();
                        } catch (Exception e) {
                            loggaEccezione(List.of(new Exception("Eccezione in: " + id + " link ")));
                        }

                        try {
                            des = element.get("descrizione") == null ? "?" + id + "?" : element.get("descrizione").toString();
                            des = des.replaceAll("<.*?>", "");
                        } catch (Exception e) {
                            des = "Eccezione in: " + id + " descrizione ";
                        }

                        LocalDateTime ld;
                        try {
                            ld = LocalDateTime.parse(data.replace("Z", ""));
                        } catch (Exception e) {
                            ld = LocalDateTime.now();
                        }

                        Show show = new Show(data, titolo, img, href, des, fonte, from, ld);
                        listShow.add(show);
                    } catch (Exception e) {
                    }
                }
                totShows.put(fonte, listShow.size() - showIniziali);
            }
        } catch (RuntimeException e) {
            skipped.add(fonte);
            loggaEccezione(List.of(e));
        }
    }

    private void leggiMailTicket() {
        String fonte = "MAILTICKET";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MMM/yyyy", Locale.ITALIAN);
        try {
            if (tipoElaborazione == TIPI_ELAB.ALL || tipoElaborazione == TIPI_ELAB.MAIL_TICKET) {
                int showIniziali = listShow.size();
                for (int ev = 1; ev <= 8; ev++) {
                    String from = "https://www.mailticket.it/esplora/" + ev;
                    String response;
                    try {
                        response = restTemplate.getForObject(from, String.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Errore chiamando: " + from + "\n" + e.getMessage());
                    }
                    Document doc = Jsoup.parse(response);
                    Elements select = doc.select("li[data-place*=Torino]");
                    for (int i = 0; i < select.size(); i++) {
                        Element element = select.get(i);
                        try {
                            String data = "";
                            String titolo = "";
                            String img = "";
                            String href = "";
                            String des = "";

                            try {
                                data = element.select(".day").text() + "/" + element.select(".month").text() + "/" + element.select(".year").text();
                            } catch (Exception e) {
                            }
                            try {
                                titolo = element.select(".info").first().select("p").first().ownText();
                            } catch (Exception e) {
                            }
                            try {
                                String tmp = element.select(".evento-search-container").attr("style").replace("background-image: url(//boxfiles.mailticket.it//", "");
                                img = "https://boxfiles.mailticket.it/" + tmp.substring(0, tmp.indexOf("?")) + "";
                            } catch (Exception e) {
                            }
                            try {
                                href = "https://www.mailticket.it/" + element.select(".info").first().select("a").first().attr("href");
                            } catch (Exception e) {
                            }
                            try {
                                des = "";
                            } catch (Exception e) {
                            }

                            LocalDateTime ld;
                            try {
                                ld = LocalDate.parse(data, formatter).atStartOfDay();
                            } catch (Exception e) {
                                ld = LocalDateTime.now();
                            }


                            Show show = new Show(data, titolo, img, href, des, fonte, from, ld);
                            listShow.add(show);
                        } catch (Exception e) {
                        }
                    }
                }
                totShows.put(fonte, listShow.size() - showIniziali);
            }
        } catch (RuntimeException e) {
            skipped.add(fonte);
            loggaEccezione(List.of(e));
        }
    }


    private void leggiNewsColosseo() {
        String fonte = "NEWS_COLOSSEO";
        try {
            if (tipoElaborazione == TIPI_ELAB.ALL || tipoElaborazione == TIPI_ELAB.NEWS_COLOSSEO) {
                String from = "https://api.teatrocolosseo.it/api/notizie";
                List<Map<String, Object>> response;
                try {
                    response = restTemplate.getForObject(from, List.class);
                } catch (Exception e) {
                    throw new RuntimeException("Errore chiamando: " + from + "\n" + e.getMessage());
                }
                for (Map<String, Object> notizia : response) {
                    String des = notizia.get("descrizione").toString();
                    des = des.replaceAll("<.*?>", "");
                    News news = new News(notizia.get("titolo").toString(), des);
                    listNews.add(news);
                }
            }
        } catch (RuntimeException e) {
            skipped.add(fonte);
            loggaEccezione(List.of(e));
        }
    }

    private ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> jsonToMap(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void leggiTicketOne() {
        String fonte = "TicketOne";
        try {
            if (tipoElaborazione == TIPI_ELAB.ALL || tipoElaborazione == TIPI_ELAB.TICKET_ONE) {
                int showIniziali = listShow.size();
                int page = 1;
                int tp;
                do {
                    String from = "https://public-api.eventim.com/websearch/search/api/exploration/v2/productGroups?webId=web__ticketone-it&language=it&page="
                            + page + "&city_ids=217&city_ids=null";
                    Map<String, Object> jsonToMap;
                    try {
                        jsonToMap = restTemplate.getForObject(from, Map.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Errore chiamando: " + from + "\n" + e.getMessage());
                    }
                    tp = (int) jsonToMap.get("totalPages");
                    List<Map<String, Object>> l = (List<Map<String, Object>>) jsonToMap.get("productGroups");
                    for (Map<String, Object> map : l) {
                        String data = "";
                        String titolo = "";
                        String img = "";
                        String href = "";
                        String des = "";

                        try {
                            data = map.get("startDate").toString();
                        } catch (Exception e) {
                        }
                        try {
                            titolo = map.get("name").toString();
                        } catch (Exception e) {
                        }
                        try {
                            img = (map.get("imageUrl") != null ? map.get("imageUrl").toString() : "");
                        } catch (Exception e) {
                        }
                        try {
                            href = map.get("link").toString();
                        } catch (Exception e) {
                        }
                        try {
                            des = (map.get("description") != null ? map.get("description").toString() : "");
                        } catch (Exception e) {
                        }
                        LocalDateTime ld;
                        try {
                            ld = OffsetDateTime.parse(data).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
                        } catch (Exception e) {
                            ld = LocalDateTime.now();
                        }

                        Show show = new Show(data, titolo, img, href, des, fonte, from, ld);
                        listShow.add(show);
                    }
                    page++;
                } while (page <= tp);
                totShows.put(fonte, listShow.size() - showIniziali);
            }
        } catch (RuntimeException e) {
            skipped.add(fonte);
            loggaEccezione(List.of(e));
        }
    }

    private void leggiTicketMaster() {
        String fonte = "TicketMaster";
        try {
            if (tipoElaborazione == TIPI_ELAB.ALL || tipoElaborazione == TIPI_ELAB.TICKET_MASTER) {
                int showIniziali = listShow.size();
                int page = 0;
                int ti;
                do {
                    String from = "https://www.ticketmaster.it/api/search/events?q=torino&region=913&sort=date&page=" + page;
                    logger.debug("{}", from);
                    Map<String, Object> jsonToMap;
                    try {
                        jsonToMap = restTemplate.getForObject(from, Map.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Errore chiamando: " + from + "\n" + e.getMessage());
                    }
                    ti = (int) jsonToMap.get("total");
                    List<Map<String, Object>> l = (List<Map<String, Object>>) jsonToMap.get("events");
                    for (Map<String, Object> map : l) {
                        String data = "";
                        String titolo = "";
                        String img = "";
                        String href = "";
                        String des = "";
                        try {
                            data = ((Map) map.get("dates")).get("startDate").toString();
                        } catch (Exception e) {
                        }
                        try {
                            titolo = map.get("title").toString();
                        } catch (Exception e) {
                        }
                        try {
                            img = "http:" + map.get("imageUrl").toString();
                        } catch (Exception e) {
                        }
                        try {
                            href = map.get("url").toString();
                        } catch (Exception e) {
                        }
                        try {
                            des = map.get("title").toString() + "/" + ((Map) map.get("venue")).get("name").toString();
                        } catch (Exception e) {
                        }

                        LocalDateTime ld;
                        try {
                            ld = LocalDateTime.parse(data.replace("Z", ""));
                        } catch (Exception e) {
                            ld = LocalDateTime.now();
                        }

                        Show show = new Show(data, titolo, img, href, des, fonte, from, ld);
                        listShow.add(show);
                    }
                    page++;
                } while (listShow.size() - showIniziali < ti);
                totShows.put(fonte, listShow.size() - showIniziali);
            }
        } catch (RuntimeException e) {
            skipped.add(fonte);
            loggaEccezione(List.of(e));
        }
    }

    private void leggiVivaTicket() {
        String fonte = "VivaTicket";
        try {
            if (tipoElaborazione == TIPI_ELAB.ALL || tipoElaborazione == TIPI_ELAB.VIVATICKET) {
                int showIniziali = listShow.size();
                int page = 1;
                int ti;
                do {
                    String from = "https://apigatewayb2cstore.vivaticket.com/api/Events/Search/" + page + "/it/it-IT?provinceCode=TO";
                    logger.debug("{}", from);
                    Map<String, Object> jsonToMap;
                    try {
                        jsonToMap = restTemplate.getForObject(from, Map.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Errore chiamando: " + from + "\n" + e.getMessage());
                    }
                    ti = (int) jsonToMap.get("totalItems");
                    List<Map<String, Object>> l = (List<Map<String, Object>>) jsonToMap.get("items");
                    for (Map<String, Object> map : l) {
                        String data = "";
                        String titolo = "";
                        String img = "";
                        String href = "";
                        String des = "";
                        try {
                            data = map.get("startDate") == null ? "-" : map.get("startDate").toString();
                        } catch (Exception e) {
                        }
                        try {
                            titolo = map.get("category").toString() + " / " + map.get("title").toString() + " / " + map.get("venueName").toString() + " (" + map.get("cityName").toString() + ")";
                        } catch (Exception e) {
                        }
                        try {
                            img = map.get("image").toString();
                        } catch (Exception e) {
                        }
                        try {
                            href = "https://www.vivaticket.com/it/Ticket/" + map.get("slug") + "/" + map.get("id");
                        } catch (Exception e) {
                        }
                        try {
                            des = map.get("title").toString();
                        } catch (Exception e) {
                        }
                        LocalDateTime ld;
                        try {
                            ld = LocalDateTime.parse(data.replace("Z", ""));
                        } catch (Exception e) {
                            ld = LocalDateTime.now();
                        }

                        Show show = new Show(data, titolo, img, href, des, fonte, from, ld);
                        listShow.add(show);
                    }
                    page++;
                } while (listShow.size() - showIniziali < ti);
                totShows.put(fonte, listShow.size() - showIniziali);
            }
        } catch (RuntimeException e) {
            skipped.add(fonte);
            loggaEccezione(List.of(e));
        }
    }

    private void leggiDice() {
        String fonte = "Dice";
        try {
            if (tipoElaborazione == TIPI_ELAB.ALL || tipoElaborazione == TIPI_ELAB.DICE) {
                String from = "https://api.dice.fm/unified_search";
                List<String> locations = List.of("Torino", "Turin");
                int showIniziali = listShow.size();
                List<Map> elementi = new ArrayList<>();
                for (String location : locations) {
                    int ti;
                    String requestBody = "{\"q\":\"" + location + "\"}";
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    //headers.set("Host", "xx"); // Esempio di header di autorizzazione
                    HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);
                    try {
                        ResponseEntity<Map> responseEntity = restTemplate.postForEntity(from, requestEntity, Map.class);

                        if (responseEntity.getBody().get("next_page_cursor") != null) {
                            throw new UnsupportedOperationException("Non gestita paginazione con Dice!");
                        }
                        elementi.addAll((List) responseEntity.getBody().get("sections"));
                    } catch (Exception e) {
                        throw new RuntimeException("Errore chiamando: [POST]" + from + "/" + requestBody + "\n" + e.getMessage());
                    }
                }
                for (Map map : elementi) {
                    if (map.get("items") != null) {
                        List<Map> items = (List) map.get("items");
                        for (Map item : items) {
                            Map single = (Map) item.get("event");
                            if (single != null) {
                                String data = "";
                                String titolo = "";
                                String img = "";
                                String href = "";
                                String des = "";
                                try {
                                    data = ((Map) ((Map) single).get("dates")).get("event_start_date").toString();
                                } catch (Exception e) {
                                }
                                try {
                                    titolo = single.get("name").toString() + " - " + ((List<Map>) single.get("venues")).get(0).get("name").toString();//name + address;
                                } catch (Exception e) {
                                }
                                try {
                                    img = ((Map) ((Map) single).get("images")).get("square").toString();
                                } catch (Exception e) {
                                }
                                try {
                                    href = ((Map) ((Map) single).get("social_links")).get("event_share").toString();
                                } catch (Exception e) {
                                }
                                try {
                                    des = ((Map) ((Map) single).get("about")).get("description").toString();
                                } catch (Exception e) {
                                }
                                LocalDateTime ld;
                                try {
                                    OffsetDateTime odt = OffsetDateTime.parse(data);
                                    ld = odt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
                                } catch (Exception e) {
                                    ld = LocalDateTime.now();
                                }


                                Show show = new Show(data, titolo, img, href, des, fonte, from, ld);
                                listShow.add(show);
                            }
                        }
                    }
                }
                totShows.put(fonte, listShow.size() - showIniziali);
            }
        } catch (RuntimeException e) {
            skipped.add(fonte);
            loggaEccezione(List.of(e));
        }
    }


    private void leggiConcordia() {
        String fonte = "CONCORDIA";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEEE dd/MM/yyyy '— Ore' HH:mm", Locale.ITALIAN);
        try {
            if (tipoElaborazione == TIPI_ELAB.ALL || tipoElaborazione == TIPI_ELAB.CONCORDIA) {
                int showIniziali = listShow.size();
                String from = "https://www.teatrodellaconcordia.it/programma-prossimi-eventi/";
                String response;
                try {
                    response = restTemplate.getForObject(from, String.class);
                } catch (Exception e) {
                    throw new RuntimeException("Errore chiamando: " + from + "\n" + e.getMessage());
                }
                Document doc = Jsoup.parse(response);
                Elements select = doc.select(".list-half-item");
                for (int i = 0; i < select.size(); i++) {
                    Element element = select.get(i);
                    try {
                        String data = "";
                        String titolo = "";
                        String img = "";
                        String href = "";
                        String des = "";
                        try {
                            data = element.select(".event-date").first().text();
                        } catch (Exception e) {
                        }
                        try {
                            titolo = element.select(".event-title").first().text();
                        } catch (Exception e) {
                        }
                        try {
                            img = element.select(".list-half-image").first().attr("style").replace("background-image:url(", "").replace(")", "");
                        } catch (Exception e) {
                        }
                        try {
                            href = element.select(".list-half-item").first().attr("onclick").replace("window.location='", "").replace("';", "");
                        } catch (Exception e) {
                        }
                        try {
                            des = "";
                        } catch (Exception e) {
                        }
                        LocalDateTime ld;
                        try {
                            String toParse;
                            String firstDatePart = data.split("-")[0].trim();
                            if (firstDatePart.indexOf(":") == -1) {
                                String timePart = data.substring(data.indexOf("Ore"));
                                toParse = firstDatePart + " — " + timePart;
                            } else {
                                toParse = data;
                            }
                            ld = LocalDateTime.parse(toParse, formatter);
                        } catch (Exception e) {
                            ld = LocalDateTime.now();
                        }

                        Show show = new Show(data, titolo, img, href, des, fonte, from, ld);
                        listShow.add(show);
                    } catch (Exception e) {
                    }
                }
                totShows.put(fonte, listShow.size() - showIniziali);
            }
        } catch (RuntimeException e) {
            skipped.add(fonte);
            loggaEccezione(List.of(e));
        }
    }


    private Step stepNews() {
        return stepBuilderFactory.get("StepNews")
                .<News, News>chunk(ConstantColossium.CHUNK)
                .reader(readerNews())
                .processor(processorNews())
                .writer(writerNews())
                .listener(stepResultListener())
                .build();
    }

    private ItemReader<News> readerNews() {
        return () -> {
            if (posizioneNews >= listNews.size()) return null;
            News newsAtt = listNews.get(posizioneNews);
            posizioneNews++;
            return newsAtt;
        };
    }

    private ItemProcessor<News, News> processorNews() {
        return item -> {
            List<News> resultList = entityManager.createQuery("select n from News n where des = :des and titolo = :titolo", News.class)
                    .setParameter("des", item.getDes())
                    .setParameter("titolo", item.getTitolo())
                    .getResultList();
            if (resultList.size() == 0) {
                item.setDataConsegna(LocalDateTime.now());
            }
            return item;
        };
    }

    private ItemWriter<News> writerNews() {
        return news ->
                news.forEach(el -> {
                    if (el.getDataConsegna() != null && !el.toString().trim().equals("")) {
                        entityManager.persist(el);
                        telegramBot.inviaMessaggio(el.toString());
                        messaggiInviati++;
                        contaEventi++;
                    }
                });
    }

    private Step stepShow() {
        return stepBuilderFactory.get("StepShow")
                .<Show, Show>chunk(ConstantColossium.CHUNK)
                .reader(readerShow())
                .processor(processorShow())
                .writer(writerShow())
                .listener(stepResultListener())
                .build();
    }

    private ItemReader<Show> readerShow() {
        return () -> {
            if (posizioneShow >= listShow.size()) return null;
            Show showAtt = listShow.get(posizioneShow);
            posizioneShow++;
            return showAtt;
        };
    }

    private ItemProcessor<Show, Show> processorShow() {
        return item -> {
            List<Show> resultList = entityManager.createQuery("select n from Show n where titolo = :titolo and fonte = :fonte and des = :des", Show.class)
                    .setParameter("titolo", item.getTitolo())
                    .setParameter("fonte", item.getFonte())
                    .setParameter("des", item.getDes())
                    .getResultList();
            if (resultList.size() == 0) {
                item.setDataConsegna(LocalDateTime.now());
            }
            return item;
        };
    }

    private ItemWriter<Show> writerShow() {
        return shows ->
                shows.forEach(el -> {
                    if (el.getDataConsegna() != null) {
                        String fonte = el.getFonte();
                        Integer tot = totNewShows.get(fonte);
                        if (tot == null) {
                            tot = 0;
                        }
                        tot++;
                        totNewShows.put(fonte, tot);
                        contaEventi++;
                        try {
                            entityManager.persist(el);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        telegramBot.sendImageToChat(el.getImg(), el.toString());
                    }
                });
    }

    private void loggaEccezione(List<Throwable> e) {
        try {
            for (Throwable throwable : e) {
                logger.error(throwable.getMessage(), e);
                Logga logga = new Logga();
                logga.setData(new Timestamp(new Date().getTime()));
                logga.setLog(throwable.getMessage());
                loggaRepository.save(logga);
            }
        } catch (Exception ex){}
    }



    private Map<String, Integer> totShows;
    private Map<String, Integer> totNewShows;
    private int messaggiInviati;
    private List<News> listNews;
    private List<Show> listShow;
    private int contaEventi;
    private int posizioneNews;
    private int posizioneShow;
    private String esito;
    private List<String> skipped;


}
