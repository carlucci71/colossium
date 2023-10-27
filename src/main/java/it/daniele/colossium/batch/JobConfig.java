
package it.daniele.colossium.batch;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

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

import it.daniele.colossium.domain.News;
import it.daniele.colossium.domain.Show;
import it.daniele.colossium.domain.TelegramMsg;
import secrets.ConstantColossium;


@SuppressWarnings("deprecation")
@Configuration
@EnableBatchProcessing
public class JobConfig extends TelegramLongPollingBot {

	RestTemplate restTemplate=new RestTemplate();
	Logger logger=LoggerFactory.getLogger(this.getClass());

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

	private JobExecutionListener jobResultListener(){
		return new JobExecutionListener() {
			public void beforeJob(JobExecution jobExecution) {
				logger.debug("Called beforeJob");
			}
			public void afterJob(JobExecution jobExecution) {
				if (jobExecution.getStatus() == BatchStatus.COMPLETED ) {
					System.out.println(totNewShows);
					inviaMessaggio("(" + contaEventi + ")\n" +
							"nuove news: " + messaggiInviati + "\n" +
							"nuovi show:" + totNewShows + "\n\n" +
							esito +
							"processati: " +
							totShows);
					logger.info("COMPLETED: {}", jobExecution);
				}
				else if (jobExecution.getStatus() == BatchStatus.FAILED) {
					inviaMessaggio("ERRORE");
					logger.info("FAILED: {}", jobExecution);
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
					esito=esito+stepExecution.getStepName() + ":" + stepExecution.getWriteCount() + "\n\r";
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
		resultList.forEach(el-> {
			if (LocalDateTime.now().isAfter(el.getDataConsegna().plusDays(ConstantColossium.DAY_TTL))) {
				el.setDataEliminazione(LocalDateTime.now());
				DeleteMessage deleteMessage = new DeleteMessage(ConstantColossium.MY_CHAT_ID, el.getId());
				try {
					entityManager.persist(el);
					execute(deleteMessage);
				} catch (TelegramApiException e) {
					logger.error(e.getMessage(),e);
					esito=esito + "WARNING CANCELLA NOTIFICHE\n\r";
				}
			}
		});
	}

	private void leggiShowColosseo() {
		int showIniziali=listShow.size();
		String fonte = "COLOSSEO";
		String from = "https://www.teatrocolosseo.it/Stagione.aspx";
		String response = restTemplate.getForObject(from, String.class);
		Document doc = Jsoup.parse(response);
		Elements select = doc.select(".boxspet");
		for (int i=0;i<select.size();i++) {
			Element element = select.get(i);
			try {
				String id = element.select(".spett-box-image").first().attr("id").replace("_imageBox", "");
				String data="";
				String titolo="";
				String img = ""; 
				String href = ""; 
				String des = "";

				try {
					data = doc.select("span[id*=" + id + "_lblDataIntro]").text();
				}
				catch (Exception e){};
				try {
					titolo = doc.select("#"+ id +"_divOverlay1 strong").first().text();
				}
				catch (Exception e){};
				try {
					img = "https://www.teatrocolosseo.it/" + element.select(".spett-box-image").first().attr("data-source");
				}
				catch (Exception e){};
				try {
					href="https://www.teatrocolosseo.it/" + doc.select("a[id*=" + id + "_hlVisualizza]").first().attr("href");
				}
				catch (Exception e){};
				try {
					des="";
				}
				catch (Exception e){};
				Show show = new Show(data, titolo, img, href, des, fonte, from);
				listShow.add(show);
			}
			catch (Exception e) {
			}
		}
		totShows.put(fonte, listShow.size()-showIniziali);
	}

	private void leggiMailTicket() {
		int showIniziali=listShow.size();
		String fonte = "MAILTICKET";
		for (int ev=1;ev<=8;ev++) {
			String from = "https://www.mailticket.it/esplora/" + ev;
			String response = restTemplate.getForObject(from, String.class);
			Document doc = Jsoup.parse(response);
			Elements select = doc.select("li[data-place*=Torino]");
			for (int i=0;i<select.size();i++) {
				Element element = select.get(i);
				try {
					String data="";
					String titolo="";
					String img = ""; 
					String href = ""; 
					String des = "";

					try {
						data = element.select(".day").text() + "/" + element.select(".month").text() + "/" + element.select(".year").text(); 
					}
					catch (Exception e){};

					try {
						titolo = element.select(".info").first().select("p").first().ownText();
					}
					catch (Exception e){};
					try {
						String tmp=element.select(".evento-search-container").attr("style").replace("background-image: url(//boxfiles.mailticket.it//", "");
						img =  "https://boxfiles.mailticket.it/" + tmp.substring(0,tmp.indexOf("?")) + "";
					}
					catch (Exception e){};
					try {
						href = "https://www.mailticket.it/" + element.select(".info").first().select("a").first().attr("href");
					}
					catch (Exception e){};
					try {
						des = ""; 
					}
					catch (Exception e){};

					Show show = new Show(data, titolo, img, href, des, fonte, from);
					listShow.add(show);
				}
				catch (Exception e) {
				}
			}
		}
		totShows.put(fonte, listShow.size()-showIniziali);
	}


	private void leggiNewsColosseo() {
		String response = restTemplate.getForObject("https://www.teatrocolosseo.it/News/Default.aspx", String.class);
		Document doc = Jsoup.parse(response);
		for (int i=0;i<ConstantColossium.MAX_NEWS;i++) {
			String textDes = doc.select("span[id*=ctl00_ContentPlaceHolder1_PageRPT_News_ctl02_ctl0" + i + "_lblDescrizione1]").text();
			String textData = doc.select("span[id*=ctl00_ContentPlaceHolder1_PageRPT_News_ctl02_ctl0" + i + "_lblData]").text();
			String textTitolo = doc.select("span[id*=ctl00_ContentPlaceHolder1_PageRPT_News_ctl02_ctl0" + i + "_lblTitolo]").text();
			News news = new News(textData, textTitolo, textDes);
			listNews.add(news);
		}
	}

	private void leggiTicketOne() {
		int showIniziali=listShow.size();
		String fonte = "TicketOne";
		int page=1;
		int tp;
		do {
			String from = "https://public-api.eventim.com/websearch/search/api/exploration/v2/productGroups?webId=web__ticketone-it&language=it&page="
					+ page + "&city_ids=217&city_ids=null";
			Map<String, Object> jsonToMap = restTemplate.getForObject(from, Map.class);
			tp = (int) jsonToMap.get("totalPages");
			List<Map<String, Object>> l = (List<Map<String, Object>>) jsonToMap.get("productGroups");
			for (Map<String, Object> map : l) {
				String data="";
				String titolo="";
				String img = ""; 
				String href = ""; 
				String des = "";

				try {
					data = map.get("startDate").toString();
				}
				catch (Exception e){};
				try {
					titolo=map.get("name").toString();
				}
				catch (Exception e){};
				try {
					img=(map.get("imageUrl") != null?map.get("imageUrl").toString():"");
				}
				catch (Exception e){};
				try {
					href=map.get("link").toString();
				}
				catch (Exception e){};
				try {
					des=(map.get("description") != null?map.get("description").toString():""); 
				}
				catch (Exception e){};
				Show show = new Show(data, titolo, img, href, des, fonte, from);
				listShow.add(show);
			}
			page++;
		} while(page<=tp);
		totShows.put(fonte, listShow.size()-showIniziali);
	}

	private void leggiTicketMaster() {
		int showIniziali=listShow.size();
		String fonte = "TicketMaster";
		int page=0;
		int ti;
		do {
			String from = "https://www.ticketmaster.it/api/search/events?q=torino&region=913&sort=date&page=" + page;
			logger.debug("{}", from);
			Map<String, Object> jsonToMap = restTemplate.getForObject(from, Map.class);
			ti = (int) jsonToMap.get("total");
			List<Map<String, Object>> l = (List<Map<String, Object>>) jsonToMap.get("events");
			for (Map<String, Object> map : l) {
				String data="";
				String titolo="";
				String img = ""; 
				String href = ""; 
				String des = "";

				try {
					data = ((Map)map.get("dates")).get("startDate").toString();
				}
				catch (Exception e){};
				try {
					titolo=map.get("title").toString();
				}
				catch (Exception e){};
				try {
					img="http:" + map.get("imageUrl").toString();
				}
				catch (Exception e){};
				try {
					href=map.get("url").toString();
				}
				catch (Exception e){};
				try {
					des=map.get("title").toString() + "/" + ((Map)map.get("venue")).get("name").toString(); 
				}
				catch (Exception e){};
				Show show = new Show(data, titolo, img, href, des, fonte, from);
				listShow.add(show);
			}
			page++;
		} while(listShow.size()-showIniziali<ti);
		totShows.put(fonte, listShow.size()-showIniziali);
	}
	private void leggiVivaTicket() {
		int showIniziali=listShow.size();
		String fonte = "VivaTicket";
		int page=1;
		int ti;
		do {
			String from = "https://apigatewayb2cstore.vivaticket.com/api/Events/Search/" + page + "/it/it-IT?provinceCode=TO";
			logger.debug("{}", from);
			Map<String, Object> jsonToMap = restTemplate.getForObject(from, Map.class);
			ti = (int) jsonToMap.get("totalItems");
			List<Map<String, Object>> l = (List<Map<String, Object>>) jsonToMap.get("items");
			for (Map<String, Object> map : l) {
				String data="";
				String titolo="";
				String img = ""; 
				String href = ""; 
				String des = "";
				try {
					data=map.get("startDate")==null?"-":map.get("startDate").toString();
				}
				catch (Exception e){};
				try {
					titolo= map.get("category").toString() + " / " + map.get("title").toString() + " / " + map.get("venueName").toString() + " (" + map.get("cityName").toString() + ")";
				}
				catch (Exception e){};
				try {
					img=map.get("image").toString();
				}
				catch (Exception e){};
				try {
					href="https://www.vivaticket.com/it/Ticket/" + map.get("slug") + "/" + map.get("id");
				}
				catch (Exception e){};
				try {
					des = map.get("title").toString();
				}
				catch (Exception e){};

				Show show = new Show(data, titolo, img, href, des, fonte, from);
				listShow.add(show);
			}
			page++;
		} while(listShow.size()-showIniziali<ti);
		totShows.put(fonte, listShow.size()-showIniziali);
	}

	private void leggiDice() {
		int showIniziali=listShow.size();
		String fonte = "Dice";
		int ti;
		String from = "https://api.dice.fm/unified_search";
		String requestBody = "{\"q\":\"torino\"}";
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Host", "xx"); // Esempio di header di autorizzazione
		HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);
		ResponseEntity<Map> responseEntity = restTemplate.postForEntity(from, requestEntity, Map.class);
		List<Map> elementi = (List) responseEntity.getBody().get("sections");
		for (Map map : elementi) {
			if(map.get("items")!=null) {
				List<Map> items = (List) map.get("items");
				for (Map item : items) {
					Map single = (Map) item.get("event");

					String data="";
					String titolo="";
					String img = ""; 
					String href = ""; 
					String des = "";
					try {
						data=((Map)((Map)single).get("dates")).get("event_start_date").toString();
					}
					catch (Exception e){};
					try {
						titolo= single.get("name").toString() + " - " + ((List<Map>)single.get("venues")).get(0).get("name").toString();//name + address;
					}
					catch (Exception e){};
					try {
						img=((Map)((Map)single).get("images")).get("square").toString();
					}
					catch (Exception e){};
					try {
						href=((Map)((Map)single).get("social_links")).get("event_share").toString();
					}
					catch (Exception e){};
					try {
						des = ((Map)((Map)single).get("about")).get("description").toString();
					}
					catch (Exception e){};

					Show show = new Show(data, titolo, img, href, des, fonte, from); 
					listShow.add(show);

				}
			};
		}

		totShows.put(fonte, listShow.size()-showIniziali);

	}


	private void leggiConcordia() {
		int showIniziali=listShow.size();
		String fonte = "CONCORDIA";
		String from = "https://www.teatrodellaconcordia.it/programma-prossimi-eventi/";
		String response = restTemplate.getForObject(from, String.class);
		Document doc = Jsoup.parse(response);
		Elements select = doc.select(".list-half-item");
		for (int i=0;i<select.size();i++) {
			Element element = select.get(i);
			try {
				String data="";
				String titolo="";
				String img = ""; 
				String href = ""; 
				String des = "";

				try {
					data = element.select(".event-date").first().text();
				}
				catch (Exception e){};
				try {
					titolo = element.select(".event-title").first().text();
				}
				catch (Exception e){};
				try {
					img = element.select(".list-half-image").first().attr("style").replace("background-image:url(", "").replace(")", "");
				}
				catch (Exception e){};
				try {
					href = element.select(".list-half-item").first().attr("onclick").replace("window.location='", "").replace("';", "");
				}
				catch (Exception e){};
				try {
					des="";
				}
				catch (Exception e){};
				Show show = new Show(data, titolo, img, href, des, fonte, from);
				listShow.add(show);
			}
			catch (Exception e) {
			}
		}
		totShows.put(fonte, listShow.size()-showIniziali);
	}

	private Step stepNews() {
		return stepBuilderFactory.get("StepNews")
				.<News, News> chunk(ConstantColossium.CHUNK)
				.reader(readerNews())
				.processor(processorNews())
				.writer(writerNews())
				.listener(stepResultListener())
				.build();
	}	

	private ItemReader<News> readerNews() {
		return () -> {
			if (posizioneNews>=listNews.size()) return null;
			News  newsAtt = listNews.get(posizioneNews);
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
			if (resultList.size()==0) {
				item.setDataConsegna(LocalDateTime.now());
			}
			return item;
		};
	}

	private ItemWriter<News> writerNews() {
		return news -> 
		news.forEach(el -> {
			if (el.getDataConsegna()!=null && !el.toString().trim().equals("")) {
				System.out.println(el);
				System.out.println(el.toString().length());
				entityManager.persist(el);
				inviaMessaggio(el.toString());
				messaggiInviati++;
				contaEventi++;
				if (messaggiInviati==ConstantColossium.MAX_NEWS) {
					inviaMessaggio("Leggi le NEWS!!!!!!!");
				}
			}
		});
	}

	private Step stepShow() {
		return stepBuilderFactory.get("StepShow")
				.<Show, Show> chunk(ConstantColossium.CHUNK)
				.reader(readerShow())
				.processor(processorShow())
				.writer(writerShow())
				.listener(stepResultListener())
				.build();
	}	

	private ItemReader<Show> readerShow() {
		return () -> {
			if (posizioneShow>=listShow.size()) return null;
			Show  showAtt = listShow.get(posizioneShow);
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
			if (resultList.size()==0) {
				item.setDataConsegna(LocalDateTime.now());
			}
			return item;
		};
	}

	private ItemWriter<Show> writerShow() {
		return shows -> 
		shows.forEach(el -> {
			if (el.getDataConsegna()!=null) {
				String fonte=el.getFonte();
				Integer tot = totNewShows.get(fonte);
				if (tot==null) {
					tot=0;
				}
				tot++;
				totNewShows.put(fonte, tot);
				contaEventi++;
				entityManager.persist(el);
				sendImageToChat(el.getImg(),el.toString());
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
		jdbcTemplate.update("insert into telegram_msg (id,data_consegna) values (?,?)", new Object[] {message.getMessageId(), LocalDateTime.now()});
	}

	private void inviaMessaggio(String msg)  {
		if (msg != null && !msg.equals("")) {
			SendMessage sendMessage = new SendMessage();
			sendMessage.enableHtml(true);
			sendMessage.setParseMode("html");
			sendMessage.setChatId(ConstantColossium.MY_CHAT_ID);
			sendMessage.setText(msg);
			try {
				Message message = execute(sendMessage);
				salvaMessaggio(message);
			} 
			catch (TelegramApiRequestException e) {
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
			}
			catch (TelegramApiException e) {
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
		} 
		catch (TelegramApiRequestException e) {
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
				}
				catch (TelegramApiException e2) {
					inviaMessaggio("**** NO IMG *** \n\r" + msg);
				}
			}
		}
		catch (TelegramApiException e) {
			try {
				sendPhoto.setPhoto(new InputFile("https://www.teatrocolosseo.it/images/throbber.gif"));
				Message message = execute(sendPhoto);
				salvaMessaggio(message);
			}
			catch (TelegramApiException e2) {
				inviaMessaggio("**** NO IMG *** \n\r" + msg);
			}
		}
	}


	@Override
	public void onUpdateReceived(Update update) {
	}

	Map<String, Integer> totShows=new HashMap<>();
	Map<String, Integer> totNewShows=new HashMap<>();
	int messaggiInviati=0;
	private List<News> listNews=new ArrayList<>(); 
	private List<Show> listShow=new ArrayList<>(); 
	int posizioneNews=0;
	int posizioneShow=0;
	String esito="";



}
