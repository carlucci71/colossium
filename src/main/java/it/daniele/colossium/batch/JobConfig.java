package it.daniele.colossium.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.daniele.colossium.domain.News;
import it.daniele.colossium.domain.SearchCriteria;
import it.daniele.colossium.domain.Show;
import it.daniele.colossium.domain.TelegramMsg;
import it.daniele.colossium.repository.RicercheRepository;
import org.apache.commons.lang3.ObjectUtils;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import secrets.ConstantColossium;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static it.daniele.colossium.domain.SearchCriteria.DATA_DEFAULT_MAX;
import static it.daniele.colossium.domain.SearchCriteria.DATA_DEFAULT_MIN;
import static it.daniele.colossium.domain.SearchCriteria.LIMIT_DEFAULT;


@SuppressWarnings("deprecation")
@Configuration
@EnableBatchProcessing
public class JobConfig extends TelegramLongPollingBot {

    public static final String TOKEN = "#@@#";
    public static final String TOKEN_ANNO = "#ANNO#";
    public static final String TOKEN_DATA = "#DATA#";
    public static final String TOKEN_MESE = "#MESE#";
    public static final String TOKEN_GIORNO = "#GIORNO#";
    public static final String TOKEN_CANCELLA = "#CANCELLA#";
    public static final String TOKEN_RICERCA = "#RICERCA#";

    JobConfig() {
        super(ConstantColossium.BOT_TOKEN);
    }

    RestTemplate restTemplate = new RestTemplate();
    Logger logger = LoggerFactory.getLogger(this.getClass());

    @PersistenceContext
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RicercheRepository ricercheRepository;

    @Autowired
    StepBuilderFactory stepBuilderFactory;

    @Autowired
    JobBuilderFactory jobBuilderFactory;


    int contaEventi;

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

    enum TIPI_ELAB {NEWS_COLOSSEO, SHOW_COLOSSEO, TICKET_ONE, SALONE_LIBRO, CONCORDIA, VIVATICKET, DICE, TICKET_MASTER, MAIL_TICKET, ALL}

    ;
    TIPI_ELAB tipoElaborazione;


    private JobExecutionListener jobResultListener() {
        return new JobExecutionListener() {
            public void beforeJob(JobExecution jobExecution) {
                tipoElaborazione = TIPI_ELAB.valueOf(jobExecution.getJobParameters().getString("tipoElaborazione"));
                logger.debug("Called beforeJob: " + tipoElaborazione);
                totShows = new HashMap<>();
                totNewShows = new HashMap<>();
                messaggiInviati = 0;
                listNews = new ArrayList<>();
                listShow = new ArrayList<>();
                posizioneNews = 0;
                posizioneShow = 0;
                esito = "";
                skipped = new ArrayList<>();
            }

            public void afterJob(JobExecution jobExecution) {
                if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
                    logger.info(totNewShows.toString());
                    inviaMessaggio("(" + contaEventi + ")\n" +
                            "skipped: " + skipped + "\n" +
                            "nuove news: " + messaggiInviati + "\n" +
                            "nuovi show:" + totNewShows + "\n\n" +
                            esito +
                            "processati: " + totShows
                    );
                    logger.info("COMPLETED: {}", jobExecution);
                } else if (jobExecution.getStatus() == BatchStatus.FAILED) {
                    inviaMessaggio("ERRORE" + jobExecution.getAllFailureExceptions());
                    logger.info("FAILED: {}", jobExecution);
                } else if (skipped.size() > 0) {
                    inviaMessaggio("SKIPPED" + skipped);
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
                    cancellaNotificheTelegramScadute();
                    return RepeatStatus.FINISHED;
                })
                .listener(stepResultListener())
                .build();
    }


    private void cancellaNotificheTelegramScadute() {
        List<TelegramMsg> resultList = entityManager.createQuery("select t from TelegramMsg t where dataEliminazione is null", TelegramMsg.class).getResultList();
        resultList.forEach(el -> {
            if (LocalDateTime.now().isAfter(el.getDataConsegna().plusDays(ConstantColossium.DAY_TTL))) {
                el.setDataEliminazione(LocalDateTime.now());
                DeleteMessage deleteMessage = new DeleteMessage(ConstantColossium.MY_CHAT_ID, el.getId());
                try {
                    entityManager.persist(el);
                    execute(deleteMessage);
                } catch (TelegramApiException e) {
                    logger.error(e.getMessage(), e);
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
                            logger.error("Eccezione in: " + id + " image ");
                        }

                        try {
                            href = element.get("link_webshop").toString();
                        } catch (Exception e) {
                            logger.error("Eccezione in: " + id + " link ");
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
            logger.error(e.getMessage(), e);
            skipped.add(fonte);
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
            logger.error(e.getMessage(), e);
            skipped.add(fonte);
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
            logger.error(e.getMessage(), e);
            skipped.add(fonte);
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
            logger.error(e.getMessage(), e);
            skipped.add(fonte);
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
            logger.error(e.getMessage(), e);
            skipped.add(fonte);
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
            logger.error(e.getMessage(), e);
            skipped.add(fonte);
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
            logger.error(e.getMessage(), e);
            skipped.add(fonte);
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
            logger.error(e.getMessage(), e);
            skipped.add(fonte);
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
                        inviaMessaggio(el.toString());
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
                        sendImageToChat(el.getImg(), el.toString());
                    }
                });
    }

    @Override
    public String getBotUsername() {
        return ConstantColossium.BOT_USERNAME;
    }

    @Override
    public String getBotToken() {
        return ConstantColossium.BOT_TOKEN;
    }

    private void salvaMessaggio(Message message) {
		/*
		con entitymanager errore perchè manca contesto transazionale			
		TelegramMsg tm = new TelegramMsg(message.getMessageId(), LocalDateTime.now());
		entityManager.persist(tm);
		 */
        jdbcTemplate.update("insert into telegram_msg (id,data_consegna) values (?,?)", new Object[]{message.getMessageId(), LocalDateTime.now()});
    }

    private void inviaMessaggio(String msg) {
        if (msg != null && !msg.equals("")) {
            SendMessage sendMessage = new SendMessage();
            sendMessage.enableHtml(true);
            sendMessage.setParseMode("html");
            sendMessage.setChatId(ConstantColossium.MY_CHAT_ID);
            sendMessage.setText(msg);
            try {
                Message message = execute(sendMessage);
                salvaMessaggio(message);
            } catch (TelegramApiRequestException e) {
                if (e.getErrorCode() == 429) {
                    int retryAfterSeconds = e.getParameters().getRetryAfter();
                    // Attendi per il periodo specificato prima di ritentare la richiesta
                    try {
                        Thread.sleep(retryAfterSeconds * 1000);
                    } catch (Exception e2) {
                        throw new RuntimeException(e2);
                    }
                    inviaMessaggio(msg);
                } else {
                    throw new RuntimeException(e);
                }
            } catch (TelegramApiException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void sendImageToChat(String imageUrl, String msg) {
        SendPhoto sendPhoto = new SendPhoto();
        sendPhoto.setChatId(ConstantColossium.MY_CHAT_ID);
        sendPhoto.setPhoto(new InputFile(imageUrl));
        sendPhoto.setCaption(msg);
        try {
            Message message = execute(sendPhoto);
            salvaMessaggio(message);
        } catch (TelegramApiRequestException e) {
            if (e.getErrorCode() == 429) {
                int retryAfterSeconds = e.getParameters().getRetryAfter();
                // Attendi per il periodo specificato prima di ritentare la richiesta
                try {
                    Thread.sleep(retryAfterSeconds * 1000);
                } catch (Exception e2) {
                    throw new RuntimeException(e2);
                }
                sendImageToChat(imageUrl, msg);
            } else {
                try {
                    sendPhoto.setPhoto(new InputFile("https://www.teatrocolosseo.it/images/throbber.gif"));
                    Message message = execute(sendPhoto);
                    salvaMessaggio(message);
                } catch (TelegramApiException e2) {
                    inviaMessaggio("**** NO IMG *** \n\r" + msg);
                }
            }
        } catch (TelegramApiException e) {
            try {
                sendPhoto.setPhoto(new InputFile("https://www.teatrocolosseo.it/images/throbber.gif"));
                Message message = execute(sendPhoto);
                salvaMessaggio(message);
            } catch (TelegramApiException e2) {
                inviaMessaggio("**** NO IMG *** \n\r" + msg);
            }
        }
    }


    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage()) {
                handleMessage(update.getMessage());
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void handleMessage(Message message) throws TelegramApiException {
        Long chatId = message.getChat().getId();

        String testo = message.getText();
        SearchCriteria criteria = userCriteria.getOrDefault(chatId, new SearchCriteria());

        if (testo.equals("/componi")) {
            userState.remove(chatId);
            userCriteria.remove(chatId);
            userMessageIdForDelete.remove(chatId);
            Message msgComponi = execute(sendInlineKeyBoard(chatId, testo, TipoKeyboard.FILTRI));
            userMessageIdOld.put(chatId, msgComponi.getMessageId());
        } else if (testo.equals("/ricerca")) {
            ricerca(chatId);
        } else {
            String stato = userState.get(chatId);
            if (stato != null) {
                SearchCriteria.FiltriRicerca filtroRicerca = SearchCriteria.FiltriRicerca.valueOf(stato);
                boolean isChanged = false;
                boolean cancellare = true;
                List<Integer> msgForDelete = userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>());
                switch (filtroRicerca) {
                    case TESTO:
                        msgForDelete.add(message.getMessageId());
                        if (!testo.equals(criteria.getTesto())) {
                            criteria.setTesto(testo);
                            isChanged = true;
                        }
                        userState.put(chatId, null);
                        break;
                    case LIMIT:
                        try {
                            msgForDelete.add(message.getMessageId());
                            Integer iTesto = Integer.parseInt(testo);
                            if (!iTesto.equals(criteria.getLimit())) {
                                criteria.setLimit(iTesto);
                                isChanged = true;
                                userState.put(chatId, null);
                            }
                        } catch (NumberFormatException e) {
                            cancellare = false;
                            Message msgValoreNumerico = execute(creaSendMessage(chatId, "Inserire un valore numerico "));
                            msgForDelete.add(msgValoreNumerico.getMessageId());
                        }
                        break;
                    default:
                        throw new RuntimeException("Filtro non gestito: " + filtroRicerca);
                }
                userCriteria.put(chatId, criteria);
                if (isChanged) {
                    aggiornaTastiera(chatId, userMessageIdOld.get(chatId), generaElencoFiltri(criteria, chatId));
                }
                if (cancellare) {
                    cancellaMessaggi(chatId);
                }
            }
        }
    }

    private void ricerca(Long chatId) throws TelegramApiException {
        SearchCriteria criteria = userCriteria.getOrDefault(chatId, new SearchCriteria());
        execute(creaSendMessage(chatId, "Avvio ricerca con criteri: " + criteria));
        List<Show> shows = new ArrayList<>();
        if (ObjectUtils.isEmpty(criteria.getFonte())
                || !criteria.getFonte().equals(Fonti.COLOSSEO_NEWS.name())) {
            shows = ricercheRepository.cercaShow(criteria);
        }
        List<News> news = new ArrayList<>();
        if (ObjectUtils.isEmpty(criteria.getFonte())
                || criteria.getFonte().equals(Fonti.COLOSSEO_NEWS.name())) {
            news = ricercheRepository.cercaNews(criteria);
        }
        int tot = shows.size() + news.size();
        if (tot > criteria.getLimit()) {
            execute(creaSendMessage(chatId, "Restringere la ricerca. Troppi elementi: " + tot));
        } else if (tot == 0) {
            execute(creaSendMessage(chatId, "Nessun elemento trovato "));
        } else {
            for (Show show : shows) {
                sendImageToChat(show.getImg(), show.toString() + "\n[ consegnato il: " + show.getDataConsegna() + " ]");
            }
            for (News el : news) {
                inviaMessaggio(el.toString() + "\n[ consegnato il: " + el.getDataConsegna() + " ]");
            }
        }
        Message msgFiltri = execute(sendInlineKeyBoard(chatId, "Componi e ricerca", TipoKeyboard.FILTRI));
        userMessageIdOld.put(chatId, msgFiltri.getMessageId());
    }

    private void handleCallback(CallbackQuery callback) throws TelegramApiException {
        Long chatId = callback.getMessage().getChatId();
        Integer messageId = callback.getMessage().getMessageId();
        String data = callback.getData();
        String stato = userState.get(chatId);
        SearchCriteria criteria = userCriteria.get(chatId);
        if (criteria == null) {
            criteria = new SearchCriteria();
            userCriteria.put(chatId, criteria);
        }
        if (stato == null) {
            if (data.startsWith(TOKEN_CANCELLA)) {
                boolean isChanged = false;
                data = data.substring(TOKEN_CANCELLA.length());
                SearchCriteria.FiltriRicerca filtroRicerca = SearchCriteria.FiltriRicerca.valueOf(data);
                switch (filtroRicerca) {
                    case TESTO:
                        if (!ObjectUtils.isEmpty(criteria.getTesto())) {
                            isChanged = true;
                            criteria.setTesto(null);
                        }
                        break;
                    case FONTE:
                        if (!ObjectUtils.isEmpty(criteria.getFonte())) {
                            isChanged = true;
                            criteria.setFonte(null);
                        }
                        break;
                    case DATA_MIN:
                        if (!criteria.getDataMin().equals(DATA_DEFAULT_MIN)) {
                            isChanged = true;
                            criteria.setDataMin(DATA_DEFAULT_MIN);
                        }
                        break;
                    case DATA_MAX:
                        if (!criteria.getDataMax().equals(DATA_DEFAULT_MAX)) {
                            isChanged = true;
                            criteria.setDataMax(DATA_DEFAULT_MAX);
                        }
                        break;
                    case DATA_CONSEGNA_MIN:
                        if (!criteria.getDataConsegnaMin().equals(DATA_DEFAULT_MIN)) {
                            isChanged = true;
                            criteria.setDataConsegnaMin(DATA_DEFAULT_MIN);
                        }
                        break;
                    case DATA_CONSEGNA_MAX:
                        if (!criteria.getDataConsegnaMax().equals(DATA_DEFAULT_MAX)) {
                            isChanged = true;
                            criteria.setDataConsegnaMax(DATA_DEFAULT_MAX);
                        }
                        break;
                    case LIMIT:
                        if (!criteria.getLimit().equals(LIMIT_DEFAULT)) {
                            isChanged = true;
                            criteria.setLimit(LIMIT_DEFAULT);
                        }
                        break;
                    default:
                        throw new RuntimeException("Filtro non gestito: " + filtroRicerca);
                }
                userCriteria.put(chatId, criteria);
                userState.put(chatId, null);
                if (isChanged) {
                    aggiornaTastiera(chatId, messageId, generaElencoFiltri(userCriteria.getOrDefault(chatId, new SearchCriteria()), chatId));
                }
            } else {
                if (data.equals(TOKEN_RICERCA)) {
                    ricerca(chatId);
                } else if (data.startsWith(TOKEN_ANNO)) {
                    String tipoRicerca = data.substring(TOKEN_ANNO.length());
                    Message msgAnni = execute(sendInlineKeyBoard(chatId, SearchCriteria.FiltriRicerca.valueOf(tipoRicerca).getDes(), TipoKeyboard.ANNI, tipoRicerca));
                    userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>()).add(msgAnni.getMessageId());
                } else if (data.startsWith(TOKEN_MESE)) {
                    String tipoRicerca = data.substring(TOKEN_MESE.length());
                    Message msgMesi = execute(sendInlineKeyBoard(chatId, SearchCriteria.FiltriRicerca.valueOf(tipoRicerca).getDes(), TipoKeyboard.MESI, tipoRicerca));
                    userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>()).add(msgMesi.getMessageId());
                } else if (data.startsWith(TOKEN_GIORNO)) {
                    String tipoRicerca = data.substring(TOKEN_GIORNO.length());
                    Message msgGiorni = execute(sendInlineKeyBoard(chatId, SearchCriteria.FiltriRicerca.valueOf(tipoRicerca).getDes(), TipoKeyboard.GIORNI, tipoRicerca));
                    userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>()).add(msgGiorni.getMessageId());
                } else if (data.startsWith(TOKEN_DATA)) {
                    data = data.substring(TOKEN_DATA.length());
                    if (data.startsWith(TOKEN_ANNO)) {
                        updateData(data, chatId, TOKEN_ANNO);
                    } else if (data.startsWith(TOKEN_MESE)) {
                        updateData(data, chatId, TOKEN_MESE);
                    } else if (data.startsWith(TOKEN_GIORNO)) {
                        updateData(data, chatId, TOKEN_GIORNO);
                    } else {
                        throw new RuntimeException("Stato inconsistente");
                    }

                } else {
                    SearchCriteria.FiltriRicerca filtroRicerca = SearchCriteria.FiltriRicerca.valueOf(data);
                    userState.put(chatId, filtroRicerca.name());
                    String now = LocalDate.now().format(FORMATTER_SIMPLE);
                    switch (filtroRicerca) {
                        case TESTO:
                          //  userMessageIdOld.put(chatId, messageId);
                            Message msgTestDaCercare = execute(creaSendMessage(chatId, "Inserisci il testo da cercare:"));
                            userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>()).add(msgTestDaCercare.getMessageId());

                            break;
                        case FONTE:
                            Message msgFonti = execute(sendInlineKeyBoard(chatId, "Fonti", TipoKeyboard.FONTI));
                            userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>()).add(msgFonti.getMessageId());
                            break;
                        case LIMIT:
                            //userMessageIdOld.put(chatId, messageId);
                            Message msgNuovoLimite = execute(creaSendMessage(chatId, "Inserisci il nuovo limite:"));
                            userMessageIdForDelete.computeIfAbsent(chatId, k -> new ArrayList<>()).add(msgNuovoLimite.getMessageId());
                            break;
                        case DATA_MIN:
                            userState.put(chatId, null);
                            if (criteria.getDataMin().compareTo(now) != 0) {
                                criteria.setDataMin(now);
                                aggiornaTastiera(chatId, messageId, generaElencoFiltri(userCriteria.getOrDefault(chatId, new SearchCriteria()), chatId));
                            }
                            break;
                        case DATA_MAX:
                            userState.put(chatId, null);
                            if (criteria.getDataMax().compareTo(now) != 0) {
                                criteria.setDataMax(now);
                                aggiornaTastiera(chatId, messageId, generaElencoFiltri(userCriteria.getOrDefault(chatId, new SearchCriteria()), chatId));
                            }
                            break;
                        case DATA_CONSEGNA_MIN:
                            userState.put(chatId, null);
                            if (criteria.getDataConsegnaMin().compareTo(now) != 0) {
                                criteria.setDataConsegnaMin(now);
                                aggiornaTastiera(chatId, messageId, generaElencoFiltri(userCriteria.getOrDefault(chatId, new SearchCriteria()), chatId));
                            }
                            break;
                        case DATA_CONSEGNA_MAX:
                            userState.put(chatId, null);
                            if (criteria.getDataConsegnaMax().compareTo(now) != 0) {
                                criteria.setDataConsegnaMax(now);
                                aggiornaTastiera(chatId, messageId, generaElencoFiltri(userCriteria.getOrDefault(chatId, new SearchCriteria()), chatId));
                            }
                            break;
                        default:
                            throw new RuntimeException("Filtro non gestito: " + filtroRicerca);
                    }
                }
            }
        } else if (stato.equals(SearchCriteria.FiltriRicerca.FONTE.name())) {
            if (!data.equals(criteria.getFonte())) {
                criteria.setFonte(data);
                aggiornaTastiera(chatId, userMessageIdOld.get(chatId), generaElencoFiltri(criteria, chatId));
                userCriteria.put(chatId, criteria);
            }
            cancellaMessaggi(chatId);
            userState.put(chatId, null);
        } else {
            throw new RuntimeException("Situazione inconsistente: " + stato);
        }
    }

    private void updateData(String data, Long chatId, String tokenElemData) throws TelegramApiException {
        data = data.substring(tokenElemData.length());
        SearchCriteria criteria = userCriteria.getOrDefault(chatId, new SearchCriteria());
        String[] split = data.split(TOKEN);
        SearchCriteria.FiltriRicerca filtroRicerca = SearchCriteria.FiltriRicerca.valueOf(split[0]);
        String extractCriteria = extractCriteria(criteria, filtroRicerca, chatId);
        LocalDate date = LocalDate.parse(extractCriteria, FORMATTER_SIMPLE);
        switch (tokenElemData) {
            case TOKEN_ANNO:
                date = date.withYear(Integer.parseInt(split[1]));
                break;
            case TOKEN_MESE:
                date = date.withMonth(Integer.parseInt(split[1]));
                break;
            case TOKEN_GIORNO:
                date = date.withDayOfMonth(Integer.parseInt(split[1]));
                break;
            default:
                throw new RuntimeException("Situazione inconsistente");
        }
        String newDate = date.format(FORMATTER_SIMPLE);
        boolean isChanged = false;
        switch (filtroRicerca) {
            case DATA_MIN:
                if (!criteria.getDataMin().equals(newDate)) {
                    criteria.setDataMin(newDate);
                    isChanged = true;
                }
                break;
            case DATA_MAX:
                if (!criteria.getDataMax().equals(newDate)) {
                    criteria.setDataMax(newDate);
                    isChanged = true;
                }
                break;
            case DATA_CONSEGNA_MIN:
                if (!criteria.getDataConsegnaMin().equals(newDate)) {
                    criteria.setDataConsegnaMin(newDate);
                    isChanged = true;
                }
                break;
            case DATA_CONSEGNA_MAX:
                if (!criteria.getDataConsegnaMax().equals(newDate)) {
                    criteria.setDataConsegnaMax(newDate);
                    isChanged = true;
                }
                break;
            default:
                throw new RuntimeException("Filtro non gestito: " + filtroRicerca);
        }
        userCriteria.put(chatId, criteria);
        userState.put(chatId, null);
        if (isChanged) {
            aggiornaTastiera(chatId, userMessageIdOld.get(chatId), generaElencoFiltri(criteria, chatId));
        }
        cancellaMessaggi(chatId);

    }

    private SendMessage creaSendMessage(long chatId, String msg) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.enableHtml(true);
        sendMessage.setParseMode("html");
        sendMessage.setChatId(Long.toString(chatId));
        sendMessage.setText(msg);
        return sendMessage;
    }

    private void cancellaMessaggi(long chatId) {
        userMessageIdForDelete.getOrDefault(chatId, new ArrayList<>()).forEach(el -> {
            DeleteMessage deleteMessage = new DeleteMessage(String.valueOf(chatId), el);
            try {
                execute(deleteMessage);
            } catch (TelegramApiException e) {
                throw new RuntimeException(e);
            }
        });
        userMessageIdForDelete.remove(chatId);

    }

    private void aggiornaTastiera(long chatId, int messageId, List<List<InlineKeyboardButton>> nuovaTastiera) throws TelegramApiException {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        inlineKeyboardMarkup.setKeyboard(nuovaTastiera);

        EditMessageReplyMarkup editMessageReplyMarkup = new EditMessageReplyMarkup();
        editMessageReplyMarkup.setChatId(String.valueOf(chatId));
        editMessageReplyMarkup.setMessageId(messageId);
        editMessageReplyMarkup.setReplyMarkup(inlineKeyboardMarkup);
        execute(editMessageReplyMarkup);
    }

    private SendMessage sendInlineKeyBoard(long chatId, String testo, TipoKeyboard tipoKeyboard, String... info) {
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboards;
        switch (tipoKeyboard) {
            case FILTRI:
                keyboards = generaElencoFiltri(userCriteria.getOrDefault(chatId, new SearchCriteria()), chatId);
                break;
            case FONTI:
                keyboards = generaElencoFonti();
                break;
            case ANNI:
                keyboards = generaAnni(info[0]);
                break;
            case MESI:
                keyboards = generaMesi(info[0]);
                break;
            case GIORNI:
                keyboards = generaGiorni(info[0]);
                break;
            default:
                throw new RuntimeException("Tipi Keyboard non gestito: " + tipoKeyboard);
        }


        SendMessage sendMessage = new SendMessage();
        sendMessage.enableHtml(true);
        sendMessage.setParseMode("html");
        sendMessage.setChatId(Long.toString(chatId));
        sendMessage.setText(testo);
        inlineKeyboardMarkup.setKeyboard(keyboards);
        sendMessage.setReplyMarkup(inlineKeyboardMarkup);
        return sendMessage;
    }

    private List<List<InlineKeyboardButton>> generaElencoFiltri(SearchCriteria criteria, Long chatId) {
        try {
            List<List<InlineKeyboardButton>> righe = new ArrayList<>();
            for (SearchCriteria.FiltriRicerca filtroRicerca : SearchCriteria.FiltriRicerca.values()) {
                List<InlineKeyboardButton> elemInRiga = new ArrayList<>();
                String extractCriteria = extractCriteria(criteria, filtroRicerca, chatId);
                if (filtroRicerca == SearchCriteria.FiltriRicerca.DATA_MIN
                        || filtroRicerca == SearchCriteria.FiltriRicerca.DATA_MAX
                        || filtroRicerca == SearchCriteria.FiltriRicerca.DATA_CONSEGNA_MIN
                        || filtroRicerca == SearchCriteria.FiltriRicerca.DATA_CONSEGNA_MAX) {
                    LocalDate date = LocalDate.parse(extractCriteria, FORMATTER_SIMPLE);
                    InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                    //TITOLO
                    inlineKeyboardButton.setText(filtroRicerca.getDes());
                    inlineKeyboardButton.setCallbackData(filtroRicerca.name());
                    elemInRiga.add(inlineKeyboardButton);
                    righe.add(elemInRiga);
                    elemInRiga = new ArrayList<>();
                    //ANNO
                    inlineKeyboardButton = new InlineKeyboardButton();
                    inlineKeyboardButton.setText(String.valueOf(date.getYear()));
                    inlineKeyboardButton.setCallbackData(TOKEN_ANNO + filtroRicerca.name());
                    elemInRiga.add(inlineKeyboardButton);
                    //MESE
                    inlineKeyboardButton = new InlineKeyboardButton();
                    inlineKeyboardButton.setText(String.valueOf(date.getMonth()));
                    inlineKeyboardButton.setCallbackData(TOKEN_MESE + filtroRicerca.name());
                    elemInRiga.add(inlineKeyboardButton);
                    inlineKeyboardButton = new InlineKeyboardButton();
                    //GIORNO
                    inlineKeyboardButton.setText(String.valueOf(date.getDayOfMonth()));
                    inlineKeyboardButton.setCallbackData(TOKEN_GIORNO + filtroRicerca.name());
                    elemInRiga.add(inlineKeyboardButton);
                    righe.add(elemInRiga);
                } else {
                    InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                    inlineKeyboardButton.setText(filtroRicerca.getDes() + ": " + extractCriteria);
                    inlineKeyboardButton.setCallbackData(filtroRicerca.name());
                    elemInRiga.add(inlineKeyboardButton);
                    inlineKeyboardButton = new InlineKeyboardButton();
                    inlineKeyboardButton.setText("\uD83D\uDDD1");
                    inlineKeyboardButton.setCallbackData(TOKEN_CANCELLA + filtroRicerca.name());
                    elemInRiga.add(inlineKeyboardButton);
                    righe.add(elemInRiga);
                }
            }

            List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
            InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
            inlineKeyboardButton.setText("RICERCA");
            inlineKeyboardButton.setCallbackData(TOKEN_RICERCA);
            keyboardButtonsRow1.add(inlineKeyboardButton);
            righe.add(keyboardButtonsRow1);


            return righe;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<List<InlineKeyboardButton>> generaElencoFonti() {
        try {
            List<List<InlineKeyboardButton>> rowList = new ArrayList<>();
            for (Fonti fonte : Fonti.values()) {
                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
                InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                inlineKeyboardButton.setText(fonte.name());
                inlineKeyboardButton.setCallbackData(fonte.name());
                keyboardButtonsRow1.add(inlineKeyboardButton);
                rowList.add(keyboardButtonsRow1);
            }
            return rowList;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<List<InlineKeyboardButton>> generaAnni(String campo) {
        try {
            List<List<InlineKeyboardButton>> righe = new ArrayList<>();
            List<InlineKeyboardButton> elemInRiga = new ArrayList<>();
            for (int i = 2021; i <= 2030; i++) {
                InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                inlineKeyboardButton.setText(String.valueOf(i));
                inlineKeyboardButton.setCallbackData(TOKEN_DATA + TOKEN_ANNO + campo + TOKEN + i);
                elemInRiga.add(inlineKeyboardButton);
                if (elemInRiga.size() == 5) {
                    righe.add(elemInRiga);
                    elemInRiga = new ArrayList<>();
                }
            }
            righe.add(elemInRiga);
            return righe;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<List<InlineKeyboardButton>> generaMesi(String campo) {
        try {
            List<List<InlineKeyboardButton>> righe = new ArrayList<>();
            List<InlineKeyboardButton> elemInRiga = new ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                inlineKeyboardButton.setText(LocalDate.of(2021, i, 1).getMonth().name());
                inlineKeyboardButton.setCallbackData(TOKEN_DATA + TOKEN_MESE + campo + TOKEN + i);
                elemInRiga.add(inlineKeyboardButton);
                if (elemInRiga.size() == 4) {
                    righe.add(elemInRiga);
                    elemInRiga = new ArrayList<>();
                }
            }
            righe.add(elemInRiga);
            return righe;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<List<InlineKeyboardButton>> generaGiorni(String campo) {
        try {
            List<List<InlineKeyboardButton>> righe = new ArrayList<>();
            List<InlineKeyboardButton> elemInRiga = new ArrayList<>();
            for (int i = 1; i <= 31; i++) {
                InlineKeyboardButton inlineKeyboardButton = new InlineKeyboardButton();
                inlineKeyboardButton.setText(String.valueOf(i));
                inlineKeyboardButton.setCallbackData(TOKEN_DATA + TOKEN_GIORNO + campo + TOKEN + i);
                elemInRiga.add(inlineKeyboardButton);
                if (elemInRiga.size() == 6) {
                    righe.add(elemInRiga);
                    elemInRiga = new ArrayList<>();
                }
            }
            righe.add(elemInRiga);
            return righe;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String extractCriteria(SearchCriteria criteria, SearchCriteria.FiltriRicerca filtroRicerca, Long chatId) {
        String ret;
        switch (filtroRicerca) {
            case TESTO:
                ret = criteria.getTesto();
                break;
            case FONTE:
                ret = criteria.getFonte();
                break;
            case DATA_MIN:
                ret = criteria.getDataMin();
                break;
            case DATA_MAX:
                ret = criteria.getDataMax();
                break;
            case DATA_CONSEGNA_MIN:
                ret = criteria.getDataConsegnaMin();
                break;
            case DATA_CONSEGNA_MAX:
                ret = criteria.getDataConsegnaMax();
                break;
            case LIMIT:
                ret = String.valueOf(criteria.getLimit());
                break;
            default:
                throw new RuntimeException("Filtro non gestito: " + filtroRicerca);
        }
        return ret == null ? "" : ret;
    }

    @PostConstruct
    public JobConfig inizializza() throws Exception {

        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
        jobConfigBot = this;
        registerBot = telegramBotsApi.registerBot(jobConfigBot);
        return jobConfigBot;
    }


    Map<String, Integer> totShows;
    Map<String, Integer> totNewShows;
    int messaggiInviati;
    private List<News> listNews;
    private List<Show> listShow;
    int posizioneNews;
    int posizioneShow;
    String esito;
    List<String> skipped;

    JobConfig jobConfigBot;
    private BotSession registerBot;


    private final Map<Long, String> userState = new HashMap<>();
    private final Map<Long, Integer> userMessageIdOld = new HashMap<>();
    private final Map<Long, List<Integer>> userMessageIdForDelete = new HashMap<>();
    private final Map<Long, SearchCriteria> userCriteria = new HashMap<>();

    public enum TipoKeyboard {FILTRI, FONTI, ANNI, MESI, GIORNI}

    public enum Fonti {CONCORDIA, DICE, TICKETONE, TICKETMASTER, VIVATICKET, MAILTICKET, COLOSSEO, COLOSSEO_NEWS}

    DateTimeFormatter FORMATTER_SIMPLE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

}
