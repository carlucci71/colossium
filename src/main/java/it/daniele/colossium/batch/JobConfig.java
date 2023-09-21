/*
https://apigatewayb2cstore.vivaticket.com/api/Events/Search/14/it/it-IT?provinceCode=TO  --> 32 a pagine e chiave al fondo totalItems
 */
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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

	Map<String, Integer> totShow=new HashMap<>();


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
					inviaMessaggio(esito + totShow);
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
					e.printStackTrace(System.out);
					esito=esito + "WARNING CANCELLA NOTIFICHE\n\r";
				}
			}
		});
	}

	private void leggiShowColosseo() {
		int showIniziali=listShow.size();
		String fonte = "COLOSSEO";
		String response = restTemplate.getForObject("https://www.teatrocolosseo.it/Stagione.aspx", String.class);
		Document doc = Jsoup.parse(response);
		Elements select = doc.select(".boxspet");
		for (int i=0;i<select.size();i++) {
			Element element = select.get(i);
			try {
				String id = element.select(".spett-box-image").first().attr("id").replace("_imageBox", "");
				String data = doc.select("span[id*=" + id + "_lblDataIntro]").text();
				String titolo = doc.select("#"+ id +"_divOverlay1 strong").first().text();
				String img = "https://www.teatrocolosseo.it/" + element.select(".spett-box-image").first().attr("data-source");
				String href="https://www.teatrocolosseo.it/" + doc.select("a[id*=" + id + "_hlVisualizza]").first().attr("href");
				Show show = new Show(data, titolo, img, href, "", fonte);
				listShow.add(show);
			}
			catch (Exception e) {
			}
		}
		totShow.put(fonte, listShow.size()-showIniziali);
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

	private ObjectMapper mapper = new ObjectMapper();
	public String toJson(Object o)
	{
		try
		{
			byte[] data = mapper.writeValueAsBytes(o);
			return new String(data);
		} catch (JsonProcessingException e)
		{
			throw new RuntimeException(e);
		} 
	}

	public Map<String, Object> jsonToMap(String json)
	{
		try
		{
			return mapper.readValue(json, new TypeReference<Map<String, Object>>(){});
		} catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	private void leggiTicketOne() {
		int showIniziali=listShow.size();
		String fonte = "TicketOne";
		int page=1;
		int tp;
		do {
			String response = restTemplate.getForObject("https://public-api.eventim.com/websearch/search/api/exploration/v2/productGroups?webId=web__ticketone-it&language=it&page="
					+ page + "&city_ids=217&city_ids=null", String.class);
			Map<String, Object> jsonToMap = jsonToMap(response);
			tp = (int) jsonToMap.get("totalPages");
			List<Map<String, Object>> l = (List<Map<String, Object>>) jsonToMap.get("productGroups");
			for (Map<String, Object> map : l) {
				Show show = new Show(map.get("startDate").toString(), 
						map.get("name").toString(), 
						(map.get("imageUrl") != null?map.get("imageUrl").toString():""), 
						map.get("link").toString(),
						(map.get("description") != null?map.get("description").toString():""), 
						fonte);
				listShow.add(show);
			}
			page++;
		} while(page<=tp);
		totShow.put(fonte, listShow.size()-showIniziali);
	}
	
	private void leggiVivaTicket() {
		int showIniziali=listShow.size();
		String fonte = "VivaTicket";
		int page=1;
		int ti;
		do {
			String url = "https://apigatewayb2cstore.vivaticket.com/api/Events/Search/" + page + "/it/it-IT?provinceCode=TO";
			System.out.println(url);
			String response = restTemplate.getForObject(url, String.class);
			Map<String, Object> jsonToMap = jsonToMap(response);
			ti = (int) jsonToMap.get("totalItems");
			List<Map<String, Object>> l = (List<Map<String, Object>>) jsonToMap.get("items");
			for (Map<String, Object> map : l) {
				Show show = new Show(map.get("startDate")==null?"-":map.get("startDate").toString(), 
						map.get("category").toString() + " / " + map.get("title").toString() + " / " + map.get("venueName").toString(), 
						map.get("image").toString(),
						null,
						map.get("title").toString(), 
						fonte);
				listShow.add(show);
//				System.out.println("1@"+show.toString().replace("\n\r", " "));
			}
			page++;
		} while(listShow.size()-showIniziali<ti);
		totShow.put(fonte, listShow.size()-showIniziali);
		
	}
	

	private void leggiConcordia() {
		int showIniziali=listShow.size();
		String fonte = "CONCORDIA";
		String response = restTemplate.getForObject("https://www.teatrodellaconcordia.it/programma-prossimi-eventi/", String.class);
		Document doc = Jsoup.parse(response);
		Elements select = doc.select(".list-half-item");
		for (int i=0;i<select.size();i++) {
			Element element = select.get(i);
			try {
				String data = element.select(".event-date").first().text();
				String titolo = element.select(".event-title").first().text();
				String img = element.select(".list-half-image").first().attr("style").replace("background-image:url(", "").replace(")", "");
				String href = element.select(".list-half-item").first().attr("onclick").replace("window.location='", "").replace("';", "");
				Show show = new Show(data, titolo, img, href, "", fonte);
				listShow.add(show);
			}
			catch (Exception e) {
			}
		}
		totShow.put(fonte, listShow.size()-showIniziali);
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
			if (el.getDataConsegna()!=null) {
				entityManager.persist(el);
				inviaMessaggio(el.toString());
				messaggiInviati++;
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
			if (false) {
				System.out.println(Instant.now() + " --> " + msg);
			} else {
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
	}

	private void sendImageToChat(String imageUrl, String msg) {
		if (false) {
			System.out.println(Instant.now() + " --> " + msg);
		} else {
//			System.out.println("2@"+msg.replace("\n\r", " "));
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
	}


	@Override
	public void onUpdateReceived(Update update) {
	}

	int messaggiInviati=0;
	private List<News> listNews=new ArrayList<>(); 
	private List<Show> listShow=new ArrayList<>(); 
	int posizioneNews=0;
	int posizioneShow=0;
	String esito="";



}
