
package it.daniele.colossium.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.daniele.colossium.domain.News;
import it.daniele.colossium.domain.Show;
import it.daniele.colossium.domain.TelegramMsg;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import secrets.ConstantColossium;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@SuppressWarnings("deprecation")
@Configuration
@EnableBatchProcessing
public class JobConfig extends TelegramLongPollingBot {

    RestTemplate restTemplate = new RestTemplate();
    Logger logger = LoggerFactory.getLogger(this.getClass());

    @PersistenceContext
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StepBuilderFactory stepBuilderFactory;

    @Autowired
    JobBuilderFactory jobBuilderFactory;


    int contaEventi;

    @Bean
    public Job createJob() {
        return jobBuilderFactory.get("MyJob")
                .incrementer(new RunIdIncrementer())
                .listener(jobResultListener())
                .start(stepInit())
                .next(stepNews())
                .next(stepShow())
                .build();
    }

    enum TIPI_ELAB {NEWS_COLOSSEO, SHOW_COLOSSEO, ENEBA, TICKET_ONE, SALONE_LIBRO, CONCORDIA, VIVATICKET, DICE, TICKET_MASTER, MAIL_TICKET, ALL}

    ;
    TIPI_ELAB tipoElaborazione;


    private JobExecutionListener jobResultListener() {
        return new JobExecutionListener() {
            public void beforeJob(JobExecution jobExecution) {
                tipoElaborazione = TIPI_ELAB.valueOf(jobExecution.getJobParameters().getString("tipoElaborazione"));
                logger.debug("Called beforeJob: " + tipoElaborazione);
            }

            public void afterJob(JobExecution jobExecution) {
                if (jobExecution.getStatus() == BatchStatus.COMPLETED && tipoElaborazione == TIPI_ELAB.ALL) {
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
//                    leggiSaloneLibro();
                    //https://www.ticket.it/_controls/Ticketit.Web.Module/SearchHelper.aspx?uid=d563e15f-d1e3-4d67-82b3-691b8b30036f&site=321&culture=it-IT&pagesize=100&idx=1&__l=5
                    leggiNewsColosseo();
                    leggiShowColosseo();
                    leggiTicketOne();
                    leggiConcordia();
                    leggiVivaTicket();
                    leggiDice();
                    leggiTicketMaster();
                    leggiMailTicket();
                    leggiEneba();
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
                            titolo = element.get("spettacolo").toString() + " - " + (element.get("compagnia")==null?"":element.get("compagnia").toString());
                        } catch (Exception e) {
                            titolo = "Eccezione in: " + id + " spettacolo ";
                        }

                        try {
                            img = "https://api.teatrocolosseo.it/api/image/" + element.get("img_copertina").toString() + "?type=spettacolo";
                        } catch (Exception e) {
                            System.out.println("Eccezione in: " + id + " image ");
                        }

                        try {
                            href = element.get("link_webshop").toString();
                        } catch (Exception e) {
                            System.out.println("Eccezione in: " + id + " link ");
                        }

                        try {
                            des = element.get("descrizione")==null?"?" + id + "?":element.get("descrizione").toString();
                            des = des.replaceAll("<.*?>", "");
                        } catch (Exception e) {
                            des = "Eccezione in: " + id + " descrizione ";
                        }
                        Show show = new Show(data, titolo, img, href, des, fonte, from);
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
                            Show show = new Show(data, titolo, img, href, des, fonte, from);
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
                    String des=notizia.get("descrizione").toString();
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

    private void leggiEneba() {
        String fonte = "Eneba";
        try {
            if (tipoElaborazione == TIPI_ELAB.ALL || tipoElaborazione == TIPI_ELAB.ENEBA) {
                int showIniziali = listShow.size();
                Map<String, Object> product;
                int pagina = 1;
                List<Map<String, Object>> ret = new ArrayList<>();
                do {
                    Map<String, Object> map;
                    String from = "https://www.eneba.com/it/store/psn?drms[]=psn&page=" + pagina + "&regions[]=italy&types[]=subscription&types[]=giftcard";
                    System.out.println(from);
                    pagina++;
                    String response = "";
                    try {
                        response = restTemplate.getForObject(from, String.class);
                        Document doc = Jsoup.parse(response);
                        String json = response.substring(response.indexOf("ROOT_QUERY") - 2);
                        json = json.substring(0, json.indexOf("</script>"));
                        map = jsonToMap(json);
                    } catch (Exception e) {
                        System.out.println(response);
                        throw new RuntimeException("Errore chiamando: " + from + "\n" + e.getMessage());
                    }
//            System.out.println(json);
//            System.out.println(map);
                    product =
                            map.entrySet()
                                    .stream()
                                    .filter(x -> x.getKey().startsWith("Product::"))
                                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                    product.forEach((k, v) -> {
                        Map r = new HashMap();
                        Map val = (Map) v;
                        Map m;
                        r.put("name", val.get("name"));
                        m = (Map) val.get("description");
                        r.put("des", m.get("title"));
                        m = (Map) val.get("cover({\"size\":300})");
                        r.put("img", m.get("src"));
                        m = (Map) val.get("cheapestAuction");
                        if (m != null) {
                            Map au = (Map) map.get(m.get("__ref"));
                            Map pr = (Map) au.get("price({\"currency\":\"EUR\"})");
                            Integer amount = (Integer) pr.get("amount");
                            r.put("price", amount / 100d);
                            ret.add(r);
                        }
                    });
                } while (product.size() > 0);
                for (Map<String, Object> map : ret) {
                    Show show = new Show("", map.get("name").toString(), map.get("img").toString(), null, map.get("des").toString(), fonte, map.get("des").toString() + " --> " + map.get("price").toString());
                    listShow.add(show);
                }
                totShows.put(fonte, listShow.size() - showIniziali);
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
                        Show show = new Show(data, titolo, img, href, des, fonte, from);
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
                        Show show = new Show(data, titolo, img, href, des, fonte, from);
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
                        Show show = new Show(data, titolo, img, href, des, fonte, from);
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
                                String address = ((List<Map>) single.get("venues")).get(0).get("address").toString();
                                boolean ok = false;
                                for (String location : locations) {
                                    if (address.toUpperCase().indexOf(location.toUpperCase()) > -1) {
                                        ok = true;
                                    }
                                }
                                if (ok) {
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
                                    Show show = new Show(data, titolo, img, href, des, fonte, from);
                                    listShow.add(show);
                                } else {
                                    System.out.println();
                                }
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
                        Show show = new Show(data, titolo, img, href, des, fonte, from);
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

    private void leggiSaloneLibro() {
        String fonte = "SALONE_LIBRO";
        try {
            if (tipoElaborazione == TIPI_ELAB.ALL || tipoElaborazione == TIPI_ELAB.SALONE_LIBRO) {
                int showIniziali = listShow.size();
                int page = 1;
                Map response;
                Integer f;
                do {
                    String from = "https://programma.salonelibro.it/api/v1/bookable-program-items";
                    try {
                        from = "https://programma.salonelibro.it/api/v1/bookable-program-items?include=&filter[after]=" + "2024-04-19%2012:41" + "&page[size]=10&page[number]=" + page;
                        response = restTemplate.getForObject(from, Map.class);
                        Map meta = (Map) response.get("meta");
                        f = (Integer) meta.get("from");
                        List<Map> data = (List<Map>) response.get("data");
                        for (Map datum : data) {
                            String title = datum.get("title").toString();
                            String subtitle = datum.get("subtitle").toString();
                            String description = datum.get("description").toString();
                            String date = datum.get("date").toString();
                            String time = datum.get("time").toString();
                            String bookable_places = datum.get("bookable_places").toString();
                            String booked_places = datum.get("booked_places").toString();
                            System.out.println(
                                    title.replace(";", "") + ";" +
                                            subtitle.replace(";", "") + ";" +
                                            description.replace(";", "") + ";" +
                                            date.replace(";", "") + ";" +
                                            time.replace(";", "") + ";" +
                                            bookable_places.replace(";", "") + ";" +
                                            booked_places.replace(";", "") + ";"
                            );
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Errore chiamando: " + from + "\n" + e.getMessage());
                    }
                    totShows.put(fonte, listShow.size() - showIniziali);
                    page++;
                } while (f != null);
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
            logger.info(item.getDes());
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
                        } catch(Exception e)
                        {
                            System.out.println();
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
    }

    Map<String, Integer> totShows = new HashMap<>();
    Map<String, Integer> totNewShows = new HashMap<>();
    int messaggiInviati = 0;
    private List<News> listNews = new ArrayList<>();
    private List<Show> listShow = new ArrayList<>();
    int posizioneNews = 0;
    int posizioneShow = 0;
    String esito = "";
    List<String> skipped = new ArrayList<>();


    

}
